import {
  initSignaling,
  registerDevice,
  onIncomingCall,
  onRemoteIceCandidates,
  onCallEnded,
  onDeviceRemoved,
  sendAnswer,
  sendIceCandidate,
  createPeerConnection,
  type IceCandidatePayload,
  type SessionDescriptionPayload,
} from '@mcc/shared';

declare global {
  interface Window {
    mcc: {
      getDeviceInfo: () => Promise<{ id: string; name: string; platform: 'windows' | 'linux' }>;
      quitApp: () => void;
    };
  }
}

function toPayload(c: RTCIceCandidate): IceCandidatePayload {
  return { candidate: c.candidate, sdpMid: c.sdpMid, sdpMLineIndex: c.sdpMLineIndex };
}

let activeCallId: string | null = null;

async function handleCall(myId: string, callId: string, offer: SessionDescriptionPayload) {
  if (activeCallId) {
    // One viewer at a time for now — the camera is already in use.
    return;
  }
  activeCallId = callId;

  let stream: MediaStream;
  try {
    // The camera/mic physically turn on here, and only here.
    stream = await navigator.mediaDevices.getUserMedia({ video: true, audio: true });
  } catch (err) {
    console.error('getUserMedia failed', err);
    activeCallId = null;
    return;
  }

  const pc = createPeerConnection();
  stream.getTracks().forEach((track) => pc.addTrack(track, stream));

  pc.onicecandidate = (e) => {
    if (e.candidate) sendIceCandidate(myId, callId, 'callee', toPayload(e.candidate));
  };

  let cleaned = false;
  const cleanup = () => {
    if (cleaned) return;
    cleaned = true;
    stream.getTracks().forEach((t) => t.stop()); // camera/mic physically turn off
    pc.close();
    unsubEnded();
    unsubCandidates();
    if (activeCallId === callId) activeCallId = null;
  };

  pc.onconnectionstatechange = () => {
    if (['disconnected', 'failed', 'closed'].includes(pc.connectionState)) cleanup();
  };

  const unsubEnded = onCallEnded(myId, callId, cleanup);
  const unsubCandidates = onRemoteIceCandidates(myId, callId, 'caller', (candidate) => {
    pc.addIceCandidate(new RTCIceCandidate(candidate)).catch((e) => console.error(e));
  });

  await pc.setRemoteDescription(offer as RTCSessionDescriptionInit);
  const answer = await pc.createAnswer();
  await pc.setLocalDescription(answer);
  sendAnswer(myId, callId, { type: answer.type, sdp: answer.sdp! });
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
