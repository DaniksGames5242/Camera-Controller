package com.mycamerascontroller.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.webrtc.*

// Background camera/mic source: registers itself in Firebase, sits idle with
// no camera/mic access until a viewer calls in, streams for exactly the
// duration of that call, then releases the camera again. Mirrors capture.ts
// on the desktop agent.
class CameraAgentService : Service() {

    private val notificationId = 1
    private val channelId = "agent_status"

    private lateinit var deviceId: String
    private lateinit var eglBase: EglBase
    private var peerConnectionFactory: PeerConnectionFactory? = null

    private var activeCallId: String? = null
    private var capturer: CameraVideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var peerConnection: PeerConnection? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    private var stopIncomingCallListener: (() -> Unit)? = null

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80")
            .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer(),
        PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443")
            .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer(),
    )

    override fun onCreate() {
        super.onCreate()
        deviceId = DeviceIdentity.deviceId(this)
        eglBase = EglBase.create()

        createNotificationChannel()
        startForegroundIdle()

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(this).createInitializationOptions()
        )
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()

        Signaling.init {
            Signaling.registerDevice(deviceId, DeviceIdentity.deviceName(this))
            stopIncomingCallListener = Signaling.onIncomingCall(deviceId) { callId, offer ->
                handleIncomingCall(callId, offer)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopIncomingCallListener?.invoke()
        Signaling.setOffline(deviceId)
        endActiveCall()
        peerConnectionFactory?.dispose()
        eglBase.release()
        super.onDestroy()
    }

    private fun handleIncomingCall(callId: String, offer: SdpPayload) {
        if (activeCallId != null) return // one viewer at a time
        activeCallId = callId

        val factory = peerConnectionFactory ?: return

        // Camera/mic physically turn on here, and only here.
        val enumerator = Camera2Enumerator(this)
        val cameraName = enumerator.deviceNames.firstOrNull { enumerator.isBackFacing(it) }
            ?: enumerator.deviceNames.firstOrNull()
            ?: run { activeCallId = null; return }

        val newCapturer = enumerator.createCapturer(cameraName, null)
        capturer = newCapturer
        val helper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        surfaceTextureHelper = helper

        val vSource = factory.createVideoSource(newCapturer.isScreencast)
        videoSource = vSource
        newCapturer.initialize(helper, this, vSource.capturerObserver)
        newCapturer.startCapture(1280, 720, 30)
        val videoTrack = factory.createVideoTrack("video0", vSource)

        val aSource = factory.createAudioSource(MediaConstraints())
        audioSource = aSource
        val audioTrack = factory.createAudioTrack("audio0", aSource)

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

        pc.addTrack(videoTrack, listOf("stream0"))
        pc.addTrack(audioTrack, listOf("stream0"))

        val callEndedListener = Signaling.onCallEnded(deviceId, callId) {
            if (activeCallId == callId) endActiveCall()
        }
        val remoteIceListener = Signaling.onRemoteIceCandidates(deviceId, callId, "caller") { c ->
            pc.addIceCandidate(IceCandidate(c.sdpMid, c.sdpMLineIndex ?: 0, c.candidate))
        }
        pendingCallEndedListener = callEndedListener
        pendingRemoteIceListener = remoteIceListener

        val remoteDesc = SessionDescription(SessionDescription.Type.OFFER, offer.sdp)
        pc.setRemoteDescription(SimpleSdpObserver(), remoteDesc)
        pc.createAnswer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription) {
                pc.setLocalDescription(SimpleSdpObserver(), desc)
                Signaling.sendAnswer(deviceId, callId, SdpPayload(desc.type.canonicalForm(), desc.description))
            }
        }, MediaConstraints())

        showStreamingNotification()
    }

    private var pendingCallEndedListener: com.google.firebase.database.ValueEventListener? = null
    private var pendingRemoteIceListener: com.google.firebase.database.ChildEventListener? = null

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

        startForegroundIdle()
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
}

open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(p0: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(p0: String?) {}
    override fun onSetFailure(p0: String?) {}
}
