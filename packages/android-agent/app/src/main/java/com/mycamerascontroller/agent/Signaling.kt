package com.mycamerascontroller.agent

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.ChildEventListener

// Kotlin mirror of packages/shared/src/{types,signaling}.ts — same RTDB schema,
// same "room" concept, so agents and clients on any platform interoperate.

// @JvmOverloads is required so a genuine zero-arg constructor exists for
// Firebase's reflection-based deserializer — a Kotlin all-defaults
// constructor alone does not produce one.
data class DeviceRecord @JvmOverloads constructor(
    var name: String = "",
    var platform: String = "android",
    var status: String = "online",
    var lastSeen: Long = 0L,
)

data class SdpPayload @JvmOverloads constructor(
    var type: String = "",
    var sdp: String = "",
)

data class IceCandidatePayload @JvmOverloads constructor(
    var candidate: String = "",
    var sdpMid: String? = null,
    var sdpMLineIndex: Int? = null,
)

data class DeviceSettings @JvmOverloads constructor(
    var width: Int? = null,
    var height: Int? = null,
    var frameRate: Int? = null,
)

object Signaling {
    private val db by lazy { FirebaseDatabase.getInstance() }
    private var signedIn = false

    /** Older than this and an unanswered call node is presumed abandoned. */
    private const val STALE_CALL_MS = 30_000L

    fun init(onReady: () -> Unit) {
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser != null) {
            signedIn = true
            onReady()
            return
        }
        auth.signInAnonymously().addOnSuccessListener {
            signedIn = true
            onReady()
        }
    }

    private fun roomRef(path: String) = db.getReference("rooms/${RoomConfig.ROOM_ID}/$path")

    fun registerDevice(deviceId: String, name: String, platform: String = "android") {
        val ref = roomRef("devices/$deviceId")
        val record = DeviceRecord(name = name, platform = platform, status = "online", lastSeen = System.currentTimeMillis())
        ref.setValue(record)
        ref.onDisconnect().updateChildren(mapOf("status" to "offline"))
    }

    fun heartbeat(deviceId: String) {
        roomRef("devices/$deviceId").updateChildren(
            mapOf("status" to "online", "lastSeen" to System.currentTimeMillis())
        )
    }

    /** One-time read of this device's current capture preferences, e.g. right before starting capture. */
    fun getDeviceSettings(deviceId: String, cb: (DeviceSettings) -> Unit) {
        roomRef("deviceSettings/$deviceId").get()
            .addOnSuccessListener { snap -> cb(snap.getValue(DeviceSettings::class.java) ?: DeviceSettings()) }
            .addOnFailureListener { cb(DeviceSettings()) }
    }

    fun setOffline(deviceId: String) {
        roomRef("devices/$deviceId").updateChildren(mapOf("status" to "offline"))
    }

    /**
     * Notifies when this device's own record has been forgotten out from
     * under it (deleted by a client while still running, not just an
     * ordinary disconnect). Only fires after the record has actually been
     * observed to exist, so it can't misfire on the initial empty snapshot
     * before registerDevice's write lands.
     */
    fun onDeviceRemoved(deviceId: String, cb: () -> Unit): ValueEventListener {
        val ref = roomRef("devices/$deviceId")
        var seenExisting = false
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    seenExisting = true
                } else if (seenExisting) {
                    cb()
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        ref.addValueEventListener(listener)
        return listener
    }

    fun stopDeviceRemovedListener(deviceId: String, listener: ValueEventListener) {
        roomRef("devices/$deviceId").removeEventListener(listener)
    }

    /**
     * Fires once per new incoming call for this device.
     * The call node is created before its offer is written (createCall vs.
     * sendAnswer/sendOffer are separate writes on the caller side), so this
     * can't just react to the child being added — it has to watch each new
     * call node's value until an offer shows up, which may already be there
     * or may land a moment later.
     */
    fun onIncomingCall(deviceId: String, cb: (callId: String, offer: SdpPayload) -> Unit): () -> Unit {
        val callsRef = roomRef("calls/$deviceId")
        val handled = mutableSetOf<String>()
        val perCallListeners = mutableMapOf<String, ValueEventListener>()

        val addedListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val callId = snapshot.key ?: return
                if (perCallListeners.containsKey(callId)) return
                val callValueListener = object : ValueEventListener {
                    override fun onDataChange(snap: DataSnapshot) {
                        if (handled.contains(callId)) return
                        val offer = snap.child("offer").getValue(SdpPayload::class.java) ?: return
                        // A call node this stale is a leftover from a caller
                        // that's long gone (e.g. the app closed without
                        // hanging up) — answering it would occupy the "one
                        // call at a time" slot forever with nothing on the
                        // other end, silently blocking every real call after.
                        val createdAt = snap.child("createdAt").getValue(Long::class.java) ?: 0L
                        if (System.currentTimeMillis() - createdAt > STALE_CALL_MS) {
                            handled.add(callId)
                            roomRef("calls/$deviceId/$callId").removeValue()
                            return
                        }
                        handled.add(callId)
                        cb(callId, offer)
                    }
                    override fun onCancelled(error: DatabaseError) {}
                }
                perCallListeners[callId] = callValueListener
                roomRef("calls/$deviceId/$callId").addValueEventListener(callValueListener)
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        }
        callsRef.addChildEventListener(addedListener)

        return {
            callsRef.removeEventListener(addedListener)
            perCallListeners.forEach { (callId, listener) ->
                roomRef("calls/$deviceId/$callId").removeEventListener(listener)
            }
        }
    }

    fun sendAnswer(targetDeviceId: String, callId: String, answer: SdpPayload) {
        roomRef("calls/$targetDeviceId/$callId").updateChildren(
            mapOf("answer" to answer, "status" to "accepted")
        )
    }

    fun sendIceCandidate(targetDeviceId: String, callId: String, role: String, candidate: IceCandidatePayload) {
        roomRef("calls/$targetDeviceId/$callId/candidates/$role").push().setValue(candidate)
    }

    fun onRemoteIceCandidates(
        targetDeviceId: String,
        callId: String,
        remoteRole: String,
        cb: (IceCandidatePayload) -> Unit,
    ): ChildEventListener {
        val ref = roomRef("calls/$targetDeviceId/$callId/candidates/$remoteRole")
        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                snapshot.getValue(IceCandidatePayload::class.java)?.let(cb)
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        }
        ref.addChildEventListener(listener)
        return listener
    }

    fun stopIceListener(targetDeviceId: String, callId: String, remoteRole: String, listener: ChildEventListener) {
        roomRef("calls/$targetDeviceId/$callId/candidates/$remoteRole").removeEventListener(listener)
    }

    /** Fires when the client hangs up (deletes the call node). */
    fun onCallEnded(targetDeviceId: String, callId: String, cb: () -> Unit): ValueEventListener {
        val ref = roomRef("calls/$targetDeviceId/$callId")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) cb()
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        ref.addValueEventListener(listener)
        return listener
    }

    fun stopCallEndedListener(targetDeviceId: String, callId: String, listener: ValueEventListener) {
        roomRef("calls/$targetDeviceId/$callId").removeEventListener(listener)
    }
}
