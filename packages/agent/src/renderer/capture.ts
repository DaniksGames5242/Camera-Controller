import {
  initSignaling,
  registerDevice,
  onIncomingCall,
  onRemoteIceCandidates,
  onCallEnded,
  onDeviceRemoved,
  getDeviceSettings,
  sendAnswer,
  sendIceCandidate,
  createPeerConnection,
  type IceCandidatePayload,
  type SessionDescriptionPayload,
  type DeviceSettings,
} from '@mcc/shared';

declare global {
  interface Window {
    mcc: {
      getDeviceInfo: () => Promise<{ id: string; name: string; platform: 'windows' | 'linux' }>;
      quitApp: () => void;
      log: (msg: string) => void;
    };
  }
}

function toPayload(c: RTCIceCandidate): IceCandidatePayload {
  return { candidate: c.candidate, sdpMid: c.sdpMid, sdpMLineIndex: c.sdpMLineIndex };
}

function log(msg: string) {
  window.mcc.log(msg);
}

let activeCallId: string | null = null;

async function handleCall(myId: string, callId: string, offer: SessionDescriptionPayload) {
  if (activeCallId) {
    // One viewer at a time for now — the camera is already in use.
    return;
  }
  activeCallId = callId;

  const settings = await getDeviceSettings(myId).catch((): DeviceSettings => ({}));
  const videoConstraints: MediaTrackConstraints = {
    width: { ideal: settings.width ?? 1280 },
    height: { ideal: settings.height ?? 720 },
    frameRate: { ideal: settings.frameRate ?? 30 },
  };

  let stream: MediaStream;
  try {
    // The camera/mic physically turn on here, and only here.
    stream = await navigator.mediaDevices.getUserMedia({ video: videoConstraints, audio: true });
  } catch (err) {
    console.error('getUserMedia failed', err);
    log(`getUserMedia FAILED: ${err}`);
    activeCallId = null;
    return;
  }
  const vTrack = stream.getVideoTracks()[0];
  log(
    `getUserMedia ok. video track: label=${vTrack?.label} readyState=${vTrack?.readyState} settings=${JSON.stringify(vTrack?.getSettings())}`
  );

  const pc = createPeerConnection();
  stream.getTracks().forEach((track) => pc.addTrack(track, stream));

  pc.onicecandidate = (e) => {
    if (e.candidate) {
      sendIceCandidate(myId, callId, 'callee', toPayload(e.candidate));
      log(`sent ICE candidate: ${e.candidate.type} ${e.candidate.protocol} ${e.candidate.candidate}`);
    } else {
      log('ICE gathering complete');
    }
  };

  let cleaned = false;
  const cleanup = () => {
    if (cleaned) return;
    cleaned = true;
    clearInterval(statsInterval);
    stream.getTracks().forEach((t) => t.stop()); // camera/mic physically turn off
    pc.close();
    unsubEnded();
    unsubCandidates();
    if (activeCallId === callId) activeCallId = null;
  };

  pc.onconnectionstatechange = () => {
    log(`connectionState=${pc.connectionState}`);
    if (['disconnected', 'failed', 'closed'].includes(pc.connectionState)) cleanup();
  };
  pc.oniceconnectionstatechange = () => log(`iceConnectionState=${pc.iceConnectionState}`);
  pc.onicegatheringstatechange = () => log(`iceGatheringState=${pc.iceGatheringState}`);

  const statsInterval = setInterval(async () => {
    const stats = await pc.getStats();
    stats.forEach((report) => {
      if (report.type === 'outbound-rtp' && report.kind === 'video') {
        log(
          `outbound video: bytesSent=${report.bytesSent} packetsSent=${report.packetsSent} framesSent=${report.framesSent} frameWidth=${report.frameWidth} frameHeight=${report.frameHeight} qualityLimitationReason=${report.qualityLimitationReason}`
        );
      }
      if (report.type === 'candidate-pair' && report.state === 'succeeded') {
        log(`active candidate-pair: bytesSent=${report.bytesSent} localCandidateId=${report.localCandidateId}`);
      }
    });
  }, 3000);

  // Trickle ICE candidates routinely arrive (and, via Firebase, are
  // delivered) before setRemoteDescription below has resolved — addIceCandidate
  // throws if called with no remote description yet, so early candidates
  // must be queued and flushed afterward rather than applied immediately.
  let remoteDescSet = false;
  const pendingCandidates: RTCIceCandidate[] = [];

  const unsubEnded = onCallEnded(myId, callId, cleanup);
  const unsubCandidates = onRemoteIceCandidates(myId, callId, 'caller', (candidate) => {
    const iceCandidate = new RTCIceCandidate(candidate);
    if (remoteDescSet) {
      pc.addIceCandidate(iceCandidate).catch((e) => log(`addIceCandidate FAILED: ${e}`));
    } else {
      pendingCandidates.push(iceCandidate);
    }
  });

  try {
    await pc.setRemoteDescription(offer as RTCSessionDescriptionInit);
    remoteDescSet = true;
    while (pendingCandidates.length) {
      const c = pendingCandidates.shift()!;
      await pc.addIceCandidate(c).catch((e) => log(`queued addIceCandidate FAILED: ${e}`));
    }
    const answer = await pc.createAnswer();
    log('createAnswer ok');
    await pc.setLocalDescription(answer);
    log('setLocalDescription ok');
    sendAnswer(myId, callId, { type: answer.type, sdp: answer.sdp! });
    log('sendAnswer ok');
  } catch (err) {
    log(`SDP negotiation FAILED: ${err}`);
    cleanup();
  }
}

async function main() {
  const info = await window.mcc.getDeviceInfo();
  await initSignaling();
  registerDevice(info.id, { name: info.name, platform: info.platform });
  onIncomingCall(info.id, (callId, offer) => {
    handleCall(info.id, callId, offer);
  });
  // Someone explicitly removed this device from a client while we're still
  // running — quit outright (not registration.stop(), which would just
  // write the record straight back as "offline") so it actually goes away
  // instead of the next heartbeat resurrecting it.
  onDeviceRemoved(info.id, () => {
    window.mcc.quitApp();
  });
}

main().catch((err) => console.error('agent renderer failed to start', err));
