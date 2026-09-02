package com.mycamerascontroller.client

import android.os.Bundle
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.ValueEventListener
import org.webrtc.*

class ViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_DEVICE_NAME = "device_name"
    }

    private lateinit var eglBase: EglBase
    private lateinit var remoteView: SurfaceViewRenderer
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var audioTransceiver: RtpTransceiver? = null
    private var micSource: AudioSource? = null
    private var micTrack: AudioTrack? = null
    private lateinit var micButton: Button

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startMic() }

    private lateinit var deviceId: String
    private var callId: String? = null
    private var answerListener: ValueEventListener? = null
    private var iceListener: ChildEventListener? = null

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80")
            .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer(),
        PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443")
            .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer(),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewer)

        deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: run { finish(); return }
        val deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME)
        title = deviceName
        findViewById<android.widget.TextView>(R.id.channelLabel).text = deviceName

        eglBase = EglBase.create()
        remoteView = findViewById(R.id.remoteView)
        remoteView.init(eglBase.eglBaseContext, null)
        remoteView.setEnableHardwareScaler(true)

        findViewById<Button>(R.id.closeButton).setOnClickListener { finish() }
        micButton = findViewById(R.id.micButton)
        micButton.setOnClickListener { onMicButtonClicked() }

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(this).createInitializationOptions()
        )
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()

        Signaling.init { startCall() }
    }

    private fun startCall() {
        val factory = peerConnectionFactory ?: return
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
                if (newState == PeerConnection.PeerConnectionState.FAILED ||
                    newState == PeerConnection.PeerConnectionState.CLOSED
                ) runOnUiThread { finish() }
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
        track.setEnabled(!track.enabled())
        micButton.setText(if (track.enabled()) R.string.disable_mic else R.string.enable_mic)
    }

    private fun startMic() {
        val factory = peerConnectionFactory ?: return
        val transceiver = audioTransceiver ?: return
        val source = factory.createAudioSource(MediaConstraints())
        val track = factory.createAudioTrack("mic0", source)
        micSource = source
        micTrack = track
        transceiver.sender.setTrack(track, false)
        micButton.setText(R.string.disable_mic)
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
