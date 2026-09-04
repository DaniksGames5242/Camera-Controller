package com.mycamerascontroller.agent

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import org.webrtc.*

// Background camera/mic source: registers itself in Firebase, sits idle with
// no camera/mic access until a viewer calls in, streams for exactly the
// duration of that call, then releases the camera again. Mirrors capture.ts
// on the desktop agent.
//
// A phone has up to two cameras worth showing, so this service runs up to
// two independent CameraChannels — one per facing, each its own Firebase
// deviceId, heartbeat, incoming-call listener and call/capture lifecycle.
// The back channel keeps the app's original (unsuffixed) deviceId so
// existing installs/settings/forget-history carry over; the front channel
// only exists (as "<id>:front") when the phone actually has a front camera.
class CameraAgentService : Service() {

    private val notificationId = 1
    private val channelId = "agent_status"

    private lateinit var eglBase: EglBase
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var channels: List<CameraChannel> = emptyList()
    private var forgotten = false
    private var connectionStateListener: com.google.firebase.database.ValueEventListener? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val heartbeatIntervalMs = 20_000L
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            // A forgotten channel's Firebase record is gone on purpose —
            // heartbeat()'s updateChildren() would otherwise silently
            // recreate it (with no name, since it only touches
            // status/lastSeen) every interval.
            channels.filterNot { it.forgottenLocally }.forEach { Signaling.heartbeat(it.deviceId) }
            mainHandler.postDelayed(this, heartbeatIntervalMs)
        }
    }

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80")
            .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer(),
        PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443")
            .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer(),
        // Mobile carriers/routers routinely block the UDP TURN candidates
        // above; this TCP-transport variant is the fallback that still
        // gets through when they do.
        PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443?transport=tcp")
            .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer(),
        // Real TLS on 443 (confirmed: the server presents a valid cert for
        // *.relay.metered.ca there) — carrier DPI that resets the plain-TCP
        // TURN candidate above because it doesn't look like HTTPS generally
        // lets this one through, since it's indistinguishable from an
        // ordinary HTTPS connection. This is usually what actually gets a
        // phone on mobile data connected.
        PeerConnection.IceServer.builder("turns:openrelay.metered.ca:443?transport=tcp")
            .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer(),
    )

    override fun onCreate() {
        super.onCreate()
        eglBase = EglBase.create()

        createNotificationChannel()
        startForegroundIdle()

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(this).createInitializationOptions()
        )
        peerConnectionFactory = PeerConnectionFactory.builder()
            // H264 high profile support on Android hardware encoders is
            // notoriously inconsistent — plenty of chipsets advertise it in
            // SDP without actually encoding to spec, which the far end's
            // decoder then silently rejects: ICE connects, bytes flow, but
            // zero frames ever decode (a recorded session comes out as a
            // 0-byte file). Baseline/constrained-baseline is what virtually
            // every hardware encoder actually implements correctly.
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, false))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()

        val baseId = DeviceIdentity.deviceId(this)
        val baseName = DeviceIdentity.deviceName(this)
        val enumerator = Camera2Enumerator(this)
        val hasFrontCamera = enumerator.deviceNames.any { enumerator.isFrontFacing(it) }

        val built = mutableListOf(
            CameraChannel(baseId, "$baseName — основная", Facing.BACK)
        )
        if (hasFrontCamera) {
            built += CameraChannel("$baseId:front", "$baseName — фронтальная", Facing.FRONT)
        }
        channels = built

        Signaling.init {
            channels.forEach { it.start() }
            mainHandler.postDelayed(heartbeatRunnable, heartbeatIntervalMs)
            // Mobile connections drop and reopen far more than Wi-Fi; on
            // reconnect, heartbeat right away rather than leaving the
            // device reading as stale/offline until the next scheduled
            // tick (up to heartbeatIntervalMs later).
            connectionStateListener = Signaling.onConnectionStateChanged { connected ->
                if (connected) channels.filterNot { it.forgottenLocally }.forEach { Signaling.heartbeat(it.deviceId) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    // Swiping the app away from Recents doesn't stop a plain (unbound)
    // foreground service on stock Android, but a number of OEM skins
    // (MIUI, EMUI, ColorOS, etc.) kill it anyway to save battery, ignoring
    // both the foreground state and the battery-optimization exemption the
    // user granted. This can't be fully prevented from app code — those
    // skins need their own "autostart"/"no restrictions" toggle enabled by
    // the user — but scheduling an immediate restart here at least recovers
    // on stock/AOSP-based devices where the service was actually stopped.
    override fun onTaskRemoved(rootIntent: Intent?) {
        val restartIntent = Intent(applicationContext, CameraAgentService::class.java)
        val pendingIntent = PendingIntent.getService(
            applicationContext, 1, restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        alarmManager.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 1000, pendingIntent)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(heartbeatRunnable)
        connectionStateListener?.let { Signaling.stopConnectionStateListener(it) }
        channels.forEach { it.stop(forgotten) }
        peerConnectionFactory?.dispose()
        eglBase.release()
        super.onDestroy()
    }

    // ---------- notification ----------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            channelId, getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun baseNotification(titleRes: Int): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(titleRes))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundIdle() {
        val notification = baseNotification(R.string.notification_title_idle)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                notificationId, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(notificationId, notification)
        }
    }

    private fun showStreamingNotification() {
        val notification = baseNotification(R.string.notification_title_streaming)
        getSystemService(NotificationManager::class.java).notify(notificationId, notification)
    }

    /** Called by a channel whenever its streaming state flips — the notification reflects "any channel active". */
    private fun refreshNotification() {
        if (channels.any { it.isStreaming }) showStreamingNotification() else startForegroundIdle()
    }

    private enum class Facing { FRONT, BACK }

    /**
     * One camera's worth of Firebase presence + on-demand capture/call
     * lifecycle. Two of these run side by side inside one service, sharing
     * the outer [peerConnectionFactory]/[eglBase], each otherwise identical
     * to what used to be the service's own single set of call state.
     */
    private inner class CameraChannel(
        val deviceId: String,
        private val displayName: String,
        private val facing: Facing,
    ) {
        var isStreaming = false
            private set

        private var activeCallId: String? = null
        private var capturer: CameraVideoCapturer? = null
        private var videoSource: VideoSource? = null
        private var audioSource: AudioSource? = null
        private var peerConnection: PeerConnection? = null
        private var surfaceTextureHelper: SurfaceTextureHelper? = null

        private var stopIncomingCallListener: (() -> Unit)? = null
        private var deviceRemovedListener: com.google.firebase.database.ValueEventListener? = null
        private var pendingCallEndedListener: com.google.firebase.database.ValueEventListener? = null
        private var pendingRemoteIceListener: com.google.firebase.database.ChildEventListener? = null

        fun start() {
            Signaling.registerDevice(deviceId, displayName)
            stopIncomingCallListener = Signaling.onIncomingCall(deviceId) { callId, offer ->
                handleIncomingCall(callId, offer)
            }
            // Someone explicitly removed this camera from a client while
            // we're still running — actually stop it, not just get
            // resurrected by the next heartbeat. If every channel has been
            // forgotten this way the whole service stops (see stopSelf()
            // below); a single-channel forget just tears down that channel.
            deviceRemovedListener = Signaling.onDeviceRemoved(deviceId) {
                forgottenLocally = true
                stop(skipOffline = true) // the record is already gone — nothing to mark offline
                if (channels.all { it.forgottenLocally }) {
                    forgotten = true
                    stopSelf()
                }
            }
        }

        var forgottenLocally = false
            private set

        fun stop(skipOffline: Boolean) {
            stopIncomingCallListener?.invoke()
            deviceRemovedListener?.let { Signaling.stopDeviceRemovedListener(deviceId, it) }
            stopIncomingCallListener = null
            deviceRemovedListener = null
            if (!skipOffline) Signaling.setOffline(deviceId)
            endActiveCall()
        }

        private fun handleIncomingCall(callId: String, offer: SdpPayload) {
            if (activeCallId != null) return // one viewer at a time per camera
            activeCallId = callId

            Signaling.getDeviceSettings(deviceId) { settings ->
                startCallWithSettings(callId, offer, settings)
            }
        }

        private fun startCallWithSettings(callId: String, offer: SdpPayload, settings: DeviceSettings) {
            val factory = peerConnectionFactory ?: return

            // Camera/mic physically turn on here, and only here.
            val enumerator = Camera2Enumerator(this@CameraAgentService)
            val cameraName = enumerator.deviceNames.firstOrNull {
                if (facing == Facing.FRONT) enumerator.isFrontFacing(it) else enumerator.isBackFacing(it)
            } ?: run { activeCallId = null; return }

            val newCapturer = enumerator.createCapturer(cameraName, object : CameraVideoCapturer.CameraEventsHandler {
                override fun onCameraError(errorDescription: String?) {
                    // Most likely cause: the other channel is already using
                    // the only camera hardware session this device can run
                    // concurrently. Fail just this call rather than crash.
                    mainHandler.post { if (activeCallId == callId) endActiveCall() }
                }
                override fun onCameraDisconnected() {}
                override fun onCameraFreezed(errorDescription: String?) {}
                override fun onCameraOpening(cameraName: String?) {}
                override fun onFirstFrameAvailable() {}
                override fun onCameraClosed() {}
            })
            capturer = newCapturer
            val helper = SurfaceTextureHelper.create("CaptureThread-$deviceId", eglBase.eglBaseContext)
            surfaceTextureHelper = helper

            val vSource = factory.createVideoSource(newCapturer.isScreencast)
            videoSource = vSource
            newCapturer.initialize(helper, this@CameraAgentService, vSource.capturerObserver)
            newCapturer.startCapture(settings.width ?: 1280, settings.height ?: 720, settings.frameRate ?: 30)
            val videoTrack = factory.createVideoTrack("video-$deviceId", vSource)

            val aSource = factory.createAudioSource(MediaConstraints())
            audioSource = aSource
            val audioTrack = factory.createAudioTrack("audio-$deviceId", aSource)

            val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
            val pc = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {
                    Signaling.sendIceCandidate(
                        deviceId, callId, "callee",
                        IceCandidatePayload(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)
                    )
                }
                override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                    if (newState == PeerConnection.PeerConnectionState.DISCONNECTED ||
                        newState == PeerConnection.PeerConnectionState.FAILED ||
                        newState == PeerConnection.PeerConnectionState.CLOSED
                    ) {
                        if (activeCallId == callId) endActiveCall()
                    }
                }
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
                override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
                override fun onAddStream(stream: MediaStream?) {}
                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onDataChannel(channel: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
            }) ?: run { endActiveCall(); return }
            peerConnection = pc

            pc.addTrack(videoTrack, listOf("stream-$deviceId"))
            pc.addTrack(audioTrack, listOf("stream-$deviceId"))

            val callEndedListener = Signaling.onCallEnded(deviceId, callId) {
                if (activeCallId == callId) endActiveCall()
            }
            // Trickle ICE candidates can arrive (via Firebase) before
            // setRemoteDescription below has actually completed — queue them and
            // flush once it succeeds rather than risk them being rejected/lost.
            var remoteDescSet = false
            val pendingCandidates = mutableListOf<IceCandidate>()
            val remoteIceListener = Signaling.onRemoteIceCandidates(deviceId, callId, "caller") { c ->
                val candidate = IceCandidate(c.sdpMid, c.sdpMLineIndex ?: 0, c.candidate)
                if (remoteDescSet) pc.addIceCandidate(candidate) else pendingCandidates.add(candidate)
            }
            pendingCallEndedListener = callEndedListener
            pendingRemoteIceListener = remoteIceListener

            val remoteDesc = SessionDescription(SessionDescription.Type.OFFER, offer.sdp)
            pc.setRemoteDescription(object : SimpleSdpObserver() {
                override fun onSetSuccess() {
                    remoteDescSet = true
                    pendingCandidates.forEach { pc.addIceCandidate(it) }
                    pendingCandidates.clear()
                }
            }, remoteDesc)
            pc.createAnswer(object : SimpleSdpObserver() {
                override fun onCreateSuccess(p0: SessionDescription?) {
                    val desc = p0 ?: return
                    pc.setLocalDescription(SimpleSdpObserver(), desc)
                    Signaling.sendAnswer(deviceId, callId, SdpPayload(desc.type.canonicalForm(), desc.description))
                }
            }, MediaConstraints())

            isStreaming = true
            refreshNotification()
        }

        private fun endActiveCall() {
            val callId = activeCallId ?: return
            activeCallId = null

            pendingCallEndedListener?.let { Signaling.stopCallEndedListener(deviceId, callId, it) }
            pendingRemoteIceListener?.let { Signaling.stopIceListener(deviceId, callId, "caller", it) }
            pendingCallEndedListener = null
            pendingRemoteIceListener = null

            capturer?.let { runCatching { it.stopCapture() } }
            capturer?.dispose()
            capturer = null
            surfaceTextureHelper?.dispose()
            surfaceTextureHelper = null
            videoSource?.dispose()
            videoSource = null
            audioSource?.dispose()
            audioSource = null
            peerConnection?.close()
            peerConnection?.dispose()
            peerConnection = null

            isStreaming = false
            refreshNotification()
        }
    }
}

open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(p0: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(p0: String?) {}
    override fun onSetFailure(p0: String?) {}
}
