import {
  initSignaling,
  listenDevices,
  createCall,
  sendOffer,
  sendIceCandidate,
  onAnswerSet,
  onRemoteIceCandidates,
  endCall,
  forgetDevice,
  createPeerConnection,
  type DeviceWithId,
  type IceCandidatePayload,
} from '@mcc/shared';

const listEl = document.getElementById('device-list') as HTMLElement;
const videoEl = document.getElementById('viewer-video') as HTMLVideoElement;
const placeholderEl = document.getElementById('viewer-placeholder') as HTMLElement;
const closeBtn = document.getElementById('close-btn') as HTMLButtonElement;

let devices: DeviceWithId[] = [];
let selectedDeviceId: string | null = null;

interface ActiveSession {
  deviceId: string;
  callId: string;
  pc: RTCPeerConnection;
  unsub: Array<() => void>;
}
let session: ActiveSession | null = null;

function toPayload(c: RTCIceCandidate): IceCandidatePayload {
  return { candidate: c.candidate, sdpMid: c.sdpMid, sdpMLineIndex: c.sdpMLineIndex };
}

function renderList() {
  listEl.innerHTML = '';
  for (const d of devices) {
    const row = document.createElement('div');
    row.className = `device ${d.status}` + (d.id === selectedDeviceId ? ' selected' : '');
    row.innerHTML = `<span class="dot ${d.status}"></span><span class="device-name">${d.name}</span>`;
    if (d.status === 'online') {
      row.onclick = () => openViewer(d.id);
    }

    // Any device can be forgotten, online or not — a running agent detects
    // this itself and shuts down (see onDeviceRemoved in the agent), an
    // offline leftover (e.g. a reinstalled agent's old record) just goes away.
    const forgetBtn = document.createElement('button');
    forgetBtn.className = 'forget-btn';
    forgetBtn.textContent = '✕';
    forgetBtn.title = 'Забыть устройство';
    forgetBtn.onclick = (e) => {
      e.stopPropagation();
      const message =
        d.status === 'online'
          ? `Удалить "${d.name}"? Агент на этом устройстве остановится и не будет виден, пока его не запустят заново.`
          : `Удалить "${d.name}" из списка? Если оно снова выйдет в сеть, появится заново.`;
      if (confirm(message)) forgetDevice(d.id);
    };
    row.appendChild(forgetBtn);
    listEl.appendChild(row);
  }
}

function closeSession() {
  if (!session) return;
  const { deviceId, callId, pc, unsub } = session;
  unsub.forEach((u) => u());
  pc.close();
  endCall(deviceId, callId);
  videoEl.srcObject = null;
  videoEl.hidden = true;
  closeBtn.hidden = true;
  placeholderEl.hidden = false;
  session = null;
  selectedDeviceId = null;
  renderList();
}

async function openViewer(deviceId: string) {
  if (session) closeSession();
  selectedDeviceId = deviceId;
  renderList();

  const callId = createCall(deviceId);
  const pc = createPeerConnection();
  const unsub: Array<() => void> = [];

  pc.ontrack = (e) => {
    videoEl.srcObject = e.streams[0];
    videoEl.hidden = false;
    placeholderEl.hidden = true;
    closeBtn.hidden = false;
  };

  pc.onicecandidate = (e) => {
    if (e.candidate) sendIceCandidate(deviceId, callId, 'caller', toPayload(e.candidate));
  };

  pc.onconnectionstatechange = () => {
    if (['failed', 'closed'].includes(pc.connectionState) && session?.callId === callId) {
      closeSession();
    }
  };

  session = { deviceId, callId, pc, unsub };

  // Trickle ICE candidates from the agent can arrive (via Firebase) before
  // this side's setRemoteDescription(answer) below has resolved —
  // addIceCandidate throws with no remote description set yet, so early
  // candidates must be queued and flushed afterward instead of dropped.
  let remoteDescSet = false;
  const pendingCandidates: RTCIceCandidate[] = [];

  unsub.push(
    onAnswerSet(deviceId, callId, async (answer) => {
      if (pc.currentRemoteDescription) return;
      await pc.setRemoteDescription(answer as RTCSessionDescriptionInit);
      remoteDescSet = true;
      while (pendingCandidates.length) {
        const c = pendingCandidates.shift()!;
        await pc.addIceCandidate(c).catch((e) => console.error(e));
      }
    })
  );
  unsub.push(
    onRemoteIceCandidates(deviceId, callId, 'callee', (candidate) => {
      const iceCandidate = new RTCIceCandidate(candidate);
      if (remoteDescSet) {
        pc.addIceCandidate(iceCandidate).catch((e) => console.error(e));
      } else {
        pendingCandidates.push(iceCandidate);
      }
    })
  );

  const offer = await pc.createOffer({ offerToReceiveVideo: true, offerToReceiveAudio: true });
  await pc.setLocalDescription(offer);
  sendOffer(deviceId, callId, { type: offer.type, sdp: offer.sdp! });
}

closeBtn.onclick = closeSession;

async function main() {
  await initSignaling();
  listenDevices((list) => {
    devices = list.sort((a, b) => a.name.localeCompare(b.name));
    renderList();
  });
}

main().catch((err) => console.error('client failed to start', err));
