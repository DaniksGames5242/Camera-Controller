package com.mycamerascontroller.client

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GestureDetectorCompat
import androidx.core.view.WindowCompat
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.ValueEventListener
import com.mycamerascontroller.client.holo.Haptics
import com.mycamerascontroller.client.holo.HoloButtonView
import com.mycamerascontroller.client.holo.HoloTicker
import com.mycamerascontroller.client.holo.HoloToastHost
import com.mycamerascontroller.client.holo.HoloViewerFrame
import com.mycamerascontroller.client.holo.Holo
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import kotlin.math.roundToInt

/**
 * A single channel, full screen.
 *
 * The WebRTC plumbing is unchanged from the plain-Material version — the
 * signalling handshake, trickle ICE, the sendrecv audio transceiver kept open
 * for a lazily-attached mic track — only the chrome around the video is new.
 *
 * The Android-specific interaction here is the swipe: closing a channel is a
 * downward drag on the video itself, released past a threshold and *thrown*
 * rather than tapped, exactly how a physical panel would be pulled down and
 * out of the way. A tap on the frame is reserved for nothing destructive.
 */
class ViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_DEVICE_NAME = "device_name"
        const val EXTRA_TINT = "tint"
    }

    private lateinit var eglBase: EglBase
    private lateinit var remoteView: SurfaceViewRenderer
    private lateinit var hud: HoloViewerFrame
    private lateinit var toasts: HoloToastHost
    private lateinit var micButton: HoloButtonView
    private lateinit var soundButton: HoloButtonView
    private lateinit var closeButton: HoloButtonView
    private lateinit var gestureDetector: GestureDetectorCompat

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var audioTransceiver: RtpTransceiver? = null
    private var micSource: AudioSource? = null
    private var micTrack: AudioTrack? = null
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var fileAudioInjector: FileAudioInjector? = null
    private var connectedAtNanos: Long = 0L
    private var closing = false

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startMic() else toasts.push(getString(R.string.toast_mic_error), HoloToastHost.Tone.ERROR) }

    private val pickAudioLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { playAudioFile(it) } }

    private lateinit var deviceId: String
    private lateinit var deviceName: String
    private var callId: String? = null
    private var answerListener: ValueEventListener? = null
    private var iceListener: ChildEventListener? = null

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

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_viewer)

        deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: run { finish(); return }
        deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: ""
        val tint = intent.getIntExtra(EXTRA_TINT, Holo.CYAN)
        title = deviceName

        hud = findViewById(R.id.hud)
        hud.deviceName = deviceName
        hud.accent = tint
        toasts = findViewById(R.id.toasts)

        eglBase = EglBase.create()
        remoteView = findViewById(R.id.remoteView)
        remoteView.init(eglBase.eglBaseContext, object : RendererCommon.RendererEvents {
            override fun onFirstFrameRendered() {}
            override fun onFrameResolutionChanged(videoWidth: Int, videoHeight: Int, rotation: Int) {
                runOnUiThread { hud.resolutionLabel = "${videoWidth}×${videoHeight}" }
            }
        })
        remoteView.setEnableHardwareScaler(true)

        closeButton = findViewById(R.id.closeButton)
        closeButton.label = getString(R.string.close)
        closeButton.glyph = "✕"
        closeButton.accent = Holo.INK_DIM
        closeButton.onActivate = { closeWithMotion() }

        micButton = findViewById(R.id.micButton)
        micButton.label = getString(R.string.enable_mic)
        micButton.glyph = "🎤"
        micButton.accent = tint
        micButton.onActivate = { onMicButtonClicked() }

        soundButton = findViewById(R.id.soundButton)
        soundButton.label = getString(R.string.play_sound_file)
        soundButton.glyph = "🔊"
        soundButton.accent = tint
        soundButton.onActivate = {
            // The WebRTC audio pipeline still touches android.media.AudioRecord
            // briefly while it's set up, even though our callback substitutes
            // its output — same RECORD_AUDIO gate as the mic button.
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                pickAudioLauncher.launch(arrayOf("audio/*"))
            } else {
                micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            }
        }

        setUpSwipeToClose()

        HoloTicker.add { _, _ ->
            hud.elapsedLabel = if (connectedAtNanos == 0L) "—" else formatElapsed(System.nanoTime() - connectedAtNanos)
        }

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(this).createInitializationOptions()
        )
        val injector = FileAudioInjector()
        fileAudioInjector = injector
        val adm = JavaAudioDeviceModule.builder(this)
            .setSampleRate(FileAudioInjector.TARGET_SAMPLE_RATE)
            .setUseStereoInput(false)
            .setAudioBufferCallback(injector)
            .createAudioDeviceModule()
        audioDeviceModule = adm
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()

        Signaling.init { startCall() }
    }

    private fun setUpSwipeToClose() {
        val threshold = resources.displayMetrics.heightPixels * 0.16f
        var startY = 0f
        var dragging = false

        gestureDetector = GestureDetectorCompat(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (vy > 1800f && (e2.y - (e1?.y ?: e2.y)) > 0) { closeWithMotion(); return true }
                return false
            }
        })

        remoteView.setOnTouchListener { v, event ->
            gestureDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startY = event.y; dragging = true }
                MotionEvent.ACTION_MOVE -> if (dragging) {
                    val dy = (event.y - startY).coerceAtLeast(0f)
                    v.translationY = dy * 0.6f
                    v.scaleX = 1f - dy / (threshold * 6f)
                    v.scaleY = v.scaleX
                    hud.alpha = (1f - dy / threshold).coerceIn(0f, 1f)
                    if (dy > threshold * 0.7f) Haptics.dragTick(v, 0.3f)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    val dy = event.y - startY
                    if (dy > threshold) closeWithMotion()
                    else { v.animate().translationY(0f).scaleX(1f).scaleY(1f).setDuration(220).start(); hud.alpha = 1f }
                }
            }
            true
        }
    }

    private fun closeWithMotion() {
        if (closing) return
        closing = true
        Haptics.tick(remoteView)
        remoteView.animate().translationY(remoteView.height * 0.5f).alpha(0f).setDuration(220)
            .withEndAction { finish(); overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out) }
            .start()
    }

    private fun formatElapsed(nanos: Long): String {
        val totalSeconds = (nanos / 1_000_000_000L).coerceAtLeast(0)
        val m = totalSeconds / 60
        val s = totalSeconds % 60
        return "%02d:%02d".format(m, s)
    }

    private fun startCall() {
        val factory = peerConnectionFactory ?: return
        Signaling.fetchIceServers(iceServers) { fetchedIceServers ->
            runOnUiThread { startCallWithIceServers(factory, fetchedIceServers) }
        }
    }

    private fun startCallWithIceServers(factory: PeerConnectionFactory, iceServers: List<PeerConnection.IceServer>) {
        val id = Signaling.createCall(deviceId)
        callId = id

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
        val pc = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                Signaling.sendIceCandidate(
                    deviceId, id, "caller",
                    IceCandidatePayload(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)
                )
            }
            override fun onTrack(transceiver: RtpTransceiver) {
                val track = transceiver.receiver.track()
                if (track is VideoTrack) {
                    track.addSink(remoteView)
                }
            }
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                runOnUiThread {
                    when (newState) {
                        PeerConnection.PeerConnectionState.CONNECTED -> {
                            hud.connected = true
                            connectedAtNanos = System.nanoTime()
                            Haptics.materialise(remoteView)
                        }
                        PeerConnection.PeerConnectionState.FAILED,
                        PeerConnection.PeerConnectionState.CLOSED -> {
                            if (!closing) {
                                toasts.push(getString(R.string.toast_channel_closed, deviceName), HoloToastHost.Tone.ERROR)
                                finish()
                            }
                        }
                        else -> Unit
                    }
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
        }) ?: run { finish(); return }
        peerConnection = pc

        pc.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
        )
        // sendrecv from the start (even with no local track yet) so pressing
        // the mic button later can attach one via setTrack() without a
        // renegotiation round-trip.
        audioTransceiver = pc.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
        )

        // Trickle ICE candidates can arrive (via Firebase) before
        // setRemoteDescription below has actually completed — queue them and
        // flush once it succeeds rather than risk them being rejected/lost.
        var remoteDescSet = false
        val pendingCandidates = mutableListOf<IceCandidate>()

        answerListener = Signaling.onAnswerSet(deviceId, id) { answer ->
            if (pc.remoteDescription != null) return@onAnswerSet
            pc.setRemoteDescription(
                object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        remoteDescSet = true
                        pendingCandidates.forEach { pc.addIceCandidate(it) }
                        pendingCandidates.clear()
                    }
                },
                SessionDescription(SessionDescription.Type.ANSWER, answer.sdp)
            )
        }
        iceListener = Signaling.onRemoteIceCandidates(deviceId, id, "callee") { c ->
            val candidate = IceCandidate(c.sdpMid, c.sdpMLineIndex ?: 0, c.candidate)
            if (remoteDescSet) pc.addIceCandidate(candidate) else pendingCandidates.add(candidate)
        }

        pc.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(p0: SessionDescription?) {
                val desc = p0 ?: return
                pc.setLocalDescription(SimpleSdpObserver(), desc)
                Signaling.sendOffer(deviceId, id, SdpPayload(desc.type.canonicalForm(), desc.description))
            }
        }, MediaConstraints())
    }

    // Requests the mic (and prompts for OS permission) only on first press,
    // not up front when the viewer opens — the audio transceiver was
    // already negotiated sendrecv, so attaching the track needs no
    // renegotiation.
    private fun onMicButtonClicked() {
        val track = micTrack
        if (track == null) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                startMic()
            } else {
                micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            }
            return
        }
        val enabled = !track.enabled()
        track.setEnabled(enabled)
        micButton.label = getString(if (enabled) R.string.disable_mic else R.string.enable_mic)
        micButton.engaged = enabled
    }

    private fun startMic() {
        val factory = peerConnectionFactory ?: return
        val transceiver = audioTransceiver ?: return
        val source = factory.createAudioSource(MediaConstraints())
        val track = factory.createAudioTrack("mic0", source)
        micSource = source
        micTrack = track
        transceiver.sender.setTrack(track, false)
        micButton.label = getString(R.string.disable_mic)
        micButton.engaged = true
    }

    /**
     * Decodes [uri] off the main thread, then feeds it into the same
     * sendrecv audio track mic talk-back uses via [FileAudioInjector] +
     * [JavaAudioDeviceModule.setAudioRecordEnabled] — see FileAudioInjector's
     * doc comment for how that substitutes for the microphone at the SDK's
     * own extension point rather than a native/JNI AudioDeviceModule.
     */
    private fun playAudioFile(uri: Uri) {
        val injector = fileAudioInjector ?: return
        if (micTrack == null) startMic() // ensures the outgoing audio track exists
        val wasMicEnabled = micTrack?.enabled() ?: false

        Thread {
            val pcm = runCatching { FileAudioInjector.decodeToPcm16Mono48k(this, uri) }.getOrNull()
            runOnUiThread {
                if (pcm == null) {
                    toasts.push(getString(R.string.toast_sound_decode_error), HoloToastHost.Tone.ERROR)
                    return@runOnUiThread
                }
                micTrack?.setEnabled(true)
                audioDeviceModule?.setAudioRecordEnabled(false)
                soundButton.engaged = true
                injector.play(pcm) {
                    runOnUiThread {
                        audioDeviceModule?.setAudioRecordEnabled(true)
                        micTrack?.setEnabled(wasMicEnabled)
                        soundButton.engaged = false
                    }
                }
            }
        }.start()
    }

    override fun onDestroy() {
        micTrack?.dispose()
        micSource?.dispose()
        val id = callId
        if (id != null) {
            answerListener?.let { al -> iceListener?.let { il -> Signaling.removeListeners(deviceId, id, al, il) } }
            Signaling.endCall(deviceId, id)
        }
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnectionFactory?.dispose()
        // The factory only borrows the ADM's native pointer (doesn't own
        // it) — we're responsible for releasing it ourselves, after the
        // factory that was using it is gone.
        audioDeviceModule?.release()
        remoteView.release()
        eglBase.release()
        super.onDestroy()
    }
}

open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(p0: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(p0: String?) {}
    override fun onSetFailure(p0: String?) {}
}
