package com.mycamerascontroller.client

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.PeerConnection
import java.net.HttpURLConnection
import java.net.URL

// Mirrors packages/shared/src/{types,signaling}.ts — same RTDB schema and
// "room" concept as the desktop client and every agent.

// @JvmOverloads is required so a genuine zero-arg constructor exists for
// Firebase's reflection-based deserializer — a Kotlin all-defaults
// constructor alone does not produce one.
data class DeviceRecord @JvmOverloads constructor(
    var name: String = "",
    var platform: String = "",
    var status: String = "offline",
    var lastSeen: Long = 0L,
)

data class DeviceWithId(val id: String, val record: DeviceRecord)

/**
 * The heartbeat is every 20s (see the agents' registerDevice). status
 * "online" alone isn't trustworthy: it only flips to "offline" via
 * onDisconnect, which fires when Firebase's server notices the socket
 * dropped — that can lag well behind the agent actually being gone
 * (uninstalled, force-killed, network cut), leaving a device that shows
 * online forever with nothing really there. Treat a stale heartbeat as
 * offline regardless of the stored status.
 */
private const val STALE_ONLINE_MS = 60_000L

fun DeviceRecord.isOnline(): Boolean =
    status == "online" && System.currentTimeMillis() - lastSeen < STALE_ONLINE_MS

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

    fun init(onReady: () -> Unit) {
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser != null) {
            onReady()
            return
        }
        auth.signInAnonymously().addOnSuccessListener { onReady() }
    }

    private fun roomRef(path: String) = db.getReference("rooms/${RoomConfig.ROOM_ID}/$path")

    fun listenDevices(cb: (List<DeviceWithId>) -> Unit): ValueEventListener {
        val ref = roomRef("devices")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val list = snapshot.children.mapNotNull { child ->
                    val record = child.getValue(DeviceRecord::class.java) ?: return@mapNotNull null
                    val id = child.key ?: return@mapNotNull null
                    DeviceWithId(id, record)
                }
                cb(list)
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        ref.addValueEventListener(listener)
        return listener
    }

    fun stopListeningDevices(listener: ValueEventListener) {
        roomRef("devices").removeEventListener(listener)
    }

    /**
     * Forgets a device record outright — e.g. one left behind by a
     * reinstalled or decommissioned agent. A device that comes back online
     * afterwards just re-registers itself.
     */
    fun forgetDevice(deviceId: String) {
        roomRef("devices/$deviceId").removeValue()
    }

    fun getDeviceSettings(deviceId: String, cb: (DeviceSettings) -> Unit) {
        roomRef("deviceSettings/$deviceId").get()
            .addOnSuccessListener { snap -> cb(snap.getValue(DeviceSettings::class.java) ?: DeviceSettings()) }
            .addOnFailureListener { cb(DeviceSettings()) }
    }

    fun setDeviceSettings(deviceId: String, settings: DeviceSettings) {
        roomRef("deviceSettings/$deviceId").setValue(settings)
    }

    fun createCall(targetDeviceId: String): String {
        val ref = roomRef("calls/$targetDeviceId").push()
        ref.setValue(mapOf("createdAt" to System.currentTimeMillis(), "status" to "pending"))
        return ref.key!!
    }

    fun sendOffer(targetDeviceId: String, callId: String, offer: SdpPayload) {
        roomRef("calls/$targetDeviceId/$callId").updateChildren(mapOf("offer" to offer))
    }

    fun sendIceCandidate(targetDeviceId: String, callId: String, role: String, candidate: IceCandidatePayload) {
        roomRef("calls/$targetDeviceId/$callId/candidates/$role").push().setValue(candidate)
    }

    fun onAnswerSet(targetDeviceId: String, callId: String, cb: (SdpPayload) -> Unit): ValueEventListener {
        val ref = roomRef("calls/$targetDeviceId/$callId")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                snapshot.child("answer").getValue(SdpPayload::class.java)?.let(cb)
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        ref.addValueEventListener(listener)
        return listener
    }

    fun onRemoteIceCandidates(
        targetDeviceId: String, callId: String, remoteRole: String, cb: (IceCandidatePayload) -> Unit,
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

    fun endCall(targetDeviceId: String, callId: String) {
        roomRef("calls/$targetDeviceId/$callId").removeValue()
    }

    fun removeListeners(targetDeviceId: String, callId: String, answerListener: ValueEventListener, iceListener: ChildEventListener) {
        roomRef("calls/$targetDeviceId/$callId").removeEventListener(answerListener)
        roomRef("calls/$targetDeviceId/$callId/candidates/callee").removeEventListener(iceListener)
    }

    // Fill in after deploying packages/turn-worker (see its README) — the
    // worker's URL is public, not sensitive.
    private const val TURN_WORKER_URL = ""

    /**
     * Fetches short-lived TURN credentials from packages/turn-worker
     * (Cloudflare Realtime — much higher-capacity, harder-to-block
     * infrastructure than the free openrelay.metered.ca relay used in
     * [fallback]). Falls back to [fallback] if the worker isn't deployed
     * yet, unreachable, or slow. Network I/O — always calls back off the
     * calling thread.
     */
    fun fetchIceServers(fallback: List<PeerConnection.IceServer>, cb: (List<PeerConnection.IceServer>) -> Unit) {
        if (TURN_WORKER_URL.isEmpty()) { cb(fallback); return }
        Thread {
            val servers = try {
                val url = URL("$TURN_WORKER_URL?room=${RoomConfig.ROOM_ID}")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4000
                    readTimeout = 4000
                    requestMethod = "GET"
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                parseIceServers(body)
            } catch (e: Exception) {
                null
            }
            cb(servers?.takeIf { it.isNotEmpty() } ?: fallback)
        }.start()
    }

    private fun parseIceServers(json: String): List<PeerConnection.IceServer> {
        val entries = JSONObject(json).getJSONArray("iceServers")
        val result = mutableListOf<PeerConnection.IceServer>()
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            val urlsField = entry.get("urls")
            val urls = if (urlsField is JSONArray) {
                (0 until urlsField.length()).map { urlsField.getString(it) }
            } else {
                listOf(urlsField.toString())
            }
            val builder = PeerConnection.IceServer.builder(urls)
            if (entry.has("username")) builder.setUsername(entry.getString("username"))
            if (entry.has("credential")) builder.setPassword(entry.getString("credential"))
            result.add(builder.createIceServer())
        }
        return result
    }
}
