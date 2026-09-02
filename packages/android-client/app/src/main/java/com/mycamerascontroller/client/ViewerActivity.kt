package com.mycamerascontroller.client

import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
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
        title = intent.getStringExtra(EXTRA_DEVICE_NAME)

        eglBase = EglBase.create()
        remoteView = findViewById(R.id.remoteView)
        remoteView.init(eglBase.eglBaseContext, null)
        remoteView.setEnableHardwareScaler(true)

        findViewById<Button>(R.id.closeButton).setOnClickListener { finish() }

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
        pc.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
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

    override fun onDestroy() {
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
