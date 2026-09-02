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
    } else {
      // Offline devices can be forgotten — e.g. a reinstalled agent leaves
      // its old record behind forever otherwise (it never gets a new
      // heartbeat, but nothing ever deletes it either).
      const forgetBtn = document.createElement('button');
      forgetBtn.className = 'forget-btn';
      forgetBtn.textContent = '✕';
      forgetBtn.title = 'Забыть устройство';
      forgetBtn.onclick = (e) => {
        e.stopPropagation();
        if (confirm(`Удалить "${d.name}" из списка? Если оно снова выйдет в сеть, появится заново.`)) {
          forgetDevice(d.id);
        }
      };
      row.appendChild(forgetBtn);
    }
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

  unsub.push(
    onAnswerSet(deviceId, callId, async (answer) => {
      if (pc.currentRemoteDescription) return;
      await pc.setRemoteDescription(answer as RTCSessionDescriptionInit);
    })
  );
  unsub.push(
    onRemoteIceCandidates(deviceId, callId, 'callee', (candidate) => {
      pc.addIceCandidate(new RTCIceCandidate(candidate)).catch((e) => console.error(e));
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
