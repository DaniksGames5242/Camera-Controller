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
  getDeviceSettings,
  setDeviceSettings,
  createPeerConnection,
  type DeviceWithId,
  type IceCandidatePayload,
  type DeviceSettings,
} from '@mcc/shared';

declare global {
  interface Window {
    mcc: {
      startRecording: (deviceName: string, startIso: string) => Promise<string>;
      writeRecordingChunk: (recordingId: string, chunk: ArrayBuffer) => void;
      finishRecording: (recordingId: string, endIso: string) => void;
    };
  }
}

const listEl = document.getElementById('device-list') as HTMLElement;
const gridEl = document.getElementById('viewer-grid') as HTMLElement;
const placeholderEl = document.getElementById('viewer-placeholder') as HTMLElement;

const settingsModal = document.getElementById('settings-modal') as HTMLElement;
const settingsTitle = document.getElementById('settings-title') as HTMLElement;
const settingsWidth = document.getElementById('settings-width') as HTMLInputElement;
const settingsHeight = document.getElementById('settings-height') as HTMLInputElement;
const settingsFps = document.getElementById('settings-fps') as HTMLInputElement;
const settingsCancel = document.getElementById('settings-cancel') as HTMLButtonElement;
const settingsSave = document.getElementById('settings-save') as HTMLButtonElement;

let devices: DeviceWithId[] = [];
let settingsTargetDeviceId: string | null = null;

interface Session {
  deviceId: string;
  deviceName: string;
  callId: string;
  pc: RTCPeerConnection;
  unsub: Array<() => void>;
  tile: HTMLElement;
  video: HTMLVideoElement;
  recDot: HTMLElement;
  recordBtn: HTMLButtonElement;
  micBtn: HTMLButtonElement;
  audioSender: RTCRtpSender;
  micTrack?: MediaStreamTrack;
  stream?: MediaStream;
  recorder?: MediaRecorder;
  recordingId?: string;
  startIso?: string;
  recordingWanted: boolean; // user can turn recording off for this session
}
const sessions = new Map<string, Session>();

function toPayload(c: RTCIceCandidate): IceCandidatePayload {
  return { candidate: c.candidate, sdpMid: c.sdpMid, sdpMLineIndex: c.sdpMLineIndex };
}

function updateGridColumns() {
  const n = sessions.size;
  const cols = n <= 1 ? 1 : Math.ceil(Math.sqrt(n));
  gridEl.style.gridTemplateColumns = `repeat(${cols}, 1fr)`;
  placeholderEl.hidden = n > 0;
}

function renderList() {
  listEl.innerHTML = '';
  for (const d of devices) {
    const row = document.createElement('div');
    const isOpen = sessions.has(d.id);
    row.className = `device ${d.status}` + (isOpen ? ' open' : '');
    // Built with real DOM nodes, not innerHTML: d.name/d.status come from an
    // agent's own Firebase record — a compromised or malicious agent could
    // set its name to arbitrary markup, and innerHTML would execute it in
    // this (privileged, filesystem-writing) renderer process.
    const dot = document.createElement('span');
    dot.className = `dot ${d.status}`;
    const nameSpan = document.createElement('span');
    nameSpan.className = 'device-name';
    nameSpan.textContent = d.name;
    row.append(dot, nameSpan);
    if (d.status === 'online' && !isOpen) {
      row.onclick = () => openViewer(d.id, d.name);
    }

    const settingsBtn = document.createElement('button');
    settingsBtn.className = 'icon-btn';
    settingsBtn.textContent = '⚙';
    settingsBtn.title = 'Настройки камеры (разрешение, FPS)';
    settingsBtn.onclick = (e) => {
      e.stopPropagation();
      openSettingsModal(d.id, d.name);
    };
    row.appendChild(settingsBtn);

    // Any device can be forgotten, online or not — a running agent detects
    // this itself and shuts down (see onDeviceRemoved in the agent), an
    // offline leftover (e.g. a reinstalled agent's old record) just goes away.
    const forgetBtn = document.createElement('button');
    forgetBtn.className = 'icon-btn';
    forgetBtn.textContent = '✕';
    forgetBtn.title = 'Забыть устройство';
    forgetBtn.onclick = (e) => {
      e.stopPropagation();
      const message =
        d.status === 'online'
          ? `Удалить "${d.name}"? Агент на этом устройстве остановится и не будет виден, пока его не запустят заново.`
          : `Удалить "${d.name}" из списка? Если оно снова выйдет в сеть, появится заново.`;
      if (confirm(message)) {
        if (sessions.has(d.id)) closeSession(d.id);
        forgetDevice(d.id);
      }
    };
    row.appendChild(forgetBtn);
    listEl.appendChild(row);
  }
}

// ---------- per-session recording ----------
// Every viewing session is recorded to disk on this PC, start to finish, so
// there's always a local record of exactly when a given camera was watched.

function startRecording(session: Session, stream: MediaStream) {
  let recorder: MediaRecorder;
  try {
    recorder = new MediaRecorder(stream, { mimeType: 'video/webm;codecs=vp8,opus' });
  } catch (err) {
    console.error('MediaRecorder unavailable, not recording this session', err);
    return;
  }
  const startIso = new Date().toISOString();
  session.startIso = startIso;
  session.recorder = recorder;

  window.mcc.startRecording(session.deviceName, startIso).then((recordingId) => {
    session.recordingId = recordingId;
    session.recDot.hidden = false;
  });

  recorder.ondataavailable = async (e) => {
    if (e.data.size === 0 || !session.recordingId) return;
    window.mcc.writeRecordingChunk(session.recordingId, await e.data.arrayBuffer());
  };
  recorder.start(1000); // flush a chunk to disk every second
}

function stopRecording(session: Session) {
  if (!session.recorder) return;
  session.recorder.stop();
  if (session.recordingId && session.startIso) {
    window.mcc.finishRecording(session.recordingId, new Date().toISOString());
  }
  session.recorder = undefined;
  session.recordingId = undefined;
  session.startIso = undefined;
  session.recDot.hidden = true;
}

function toggleRecording(session: Session) {
  if (session.recorder) {
    session.recordingWanted = false;
    stopRecording(session);
    session.recordBtn.textContent = 'Включить запись';
  } else if (session.stream) {
    session.recordingWanted = true;
    startRecording(session, session.stream);
    session.recordBtn.textContent = 'Остановить запись';
  }
}

// ---------- talk-back microphone ----------
// Requests the mic (and prompts for OS permission) only on first press, not
// up front when the viewer opens — the audio transceiver was already
// negotiated sendrecv, so attaching the track here needs no renegotiation.

async function toggleMic(session: Session) {
  if (!session.micTrack) {
    let micStream: MediaStream;
    try {
      micStream = await navigator.mediaDevices.getUserMedia({ audio: true });
    } catch (err) {
      console.error('mic getUserMedia failed', err);
      alert('Не удалось получить доступ к микрофону.');
      return;
    }
    session.micTrack = micStream.getAudioTracks()[0];
    await session.audioSender.replaceTrack(session.micTrack);
    session.micBtn.textContent = '🎤 Выключить микрофон';
    return;
  }
  session.micTrack.enabled = !session.micTrack.enabled;
  session.micBtn.textContent = session.micTrack.enabled ? '🎤 Выключить микрофон' : '🎤 Включить микрофон';
}

// ---------- viewing sessions ----------

function closeSession(deviceId: string) {
  const session = sessions.get(deviceId);
  if (!session) return;
  stopRecording(session);
  session.micTrack?.stop();
  session.unsub.forEach((u) => u());
  session.pc.close();
  endCall(session.deviceId, session.callId);
  session.tile.remove();
  sessions.delete(deviceId);
  updateGridColumns();
  renderList();
}

async function openViewer(deviceId: string, deviceName: string) {
  if (sessions.has(deviceId)) return;

  const tile = document.createElement('div');
  tile.className = 'tile';
  const video = document.createElement('video');
  video.autoplay = true;
  video.playsInline = true;
  const label = document.createElement('div');
  label.className = 'tile-label';
  const recDot = document.createElement('span');
  recDot.className = 'rec-dot';
  recDot.hidden = true;
  recDot.title = 'Запись идёт';
  const labelText = document.createElement('span');
  labelText.textContent = deviceName;
  label.append(recDot, labelText);
  const actions = document.createElement('div');
  actions.className = 'tile-actions';
  const recordBtn = document.createElement('button');
  recordBtn.className = 'tile-btn';
  recordBtn.textContent = 'Остановить запись';
  recordBtn.onclick = () => toggleRecording(session);
  const micBtn = document.createElement('button');
  micBtn.className = 'tile-btn';
  micBtn.textContent = '🎤 Включить микрофон';
  micBtn.onclick = () => toggleMic(session);
  const closeBtn = document.createElement('button');
  closeBtn.className = 'tile-btn';
  closeBtn.textContent = 'Закрыть';
  closeBtn.onclick = () => closeSession(deviceId);
  actions.append(recordBtn, micBtn, closeBtn);
  tile.append(video, label, actions);
  gridEl.appendChild(tile);
  updateGridColumns();

  const callId = createCall(deviceId);
  const pc = createPeerConnection();
  const unsub: Array<() => void> = [];

  // Audio is negotiated sendrecv from the start (even with no local track
  // yet) so pressing the mic button later can just attach a track via
  // replaceTrack() — no renegotiation round-trip needed. Video stays
  // receive-only since the client never sends its own camera.
  const audioTransceiver = pc.addTransceiver('audio', { direction: 'sendrecv' });
  pc.addTransceiver('video', { direction: 'recvonly' });

  const session: Session = {
    deviceId,
    deviceName,
    callId,
    pc,
    unsub,
    tile,
    video,
    recDot,
    recordBtn,
    micBtn,
    audioSender: audioTransceiver.sender,
    recordingWanted: true,
  };
  sessions.set(deviceId, session);
  renderList();

  pc.ontrack = (e) => {
    if (e.track.kind !== 'video') return; // the agent's mic reply, if any, needs no local playback here
    video.srcObject = e.streams[0];
    session.stream = e.streams[0];
    if (!session.recorder && session.recordingWanted) startRecording(session, e.streams[0]);
  };

  // Trickle ICE candidates from the agent can arrive (via Firebase) before
  // this side's setRemoteDescription(answer) below has resolved —
  // addIceCandidate throws with no remote description set yet, so early
  // candidates must be queued and flushed afterward instead of dropped.
  let remoteDescSet = false;
  const pendingCandidates: RTCIceCandidate[] = [];

  pc.onicecandidate = (e) => {
    if (e.candidate) sendIceCandidate(deviceId, callId, 'caller', toPayload(e.candidate));
  };

  pc.onconnectionstatechange = () => {
    if (['failed', 'closed'].includes(pc.connectionState)) closeSession(deviceId);
  };

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

  const offer = await pc.createOffer();
  await pc.setLocalDescription(offer);
  sendOffer(deviceId, callId, { type: offer.type, sdp: offer.sdp! });
}

// ---------- per-device capture settings ----------

function openSettingsModal(deviceId: string, deviceName: string) {
  settingsTargetDeviceId = deviceId;
  settingsTitle.textContent = `Настройки — ${deviceName}`;
  settingsWidth.value = '';
  settingsHeight.value = '';
  settingsFps.value = '';
  getDeviceSettings(deviceId).then((s) => {
    if (settingsTargetDeviceId !== deviceId) return; // modal was reassigned/closed meanwhile
    if (s.width) settingsWidth.value = String(s.width);
    if (s.height) settingsHeight.value = String(s.height);
    if (s.frameRate) settingsFps.value = String(s.frameRate);
  });
  settingsModal.hidden = false;
}

function closeSettingsModal() {
  settingsModal.hidden = true;
  settingsTargetDeviceId = null;
}

settingsCancel.onclick = closeSettingsModal;
settingsSave.onclick = () => {
  if (!settingsTargetDeviceId) return;
  // Firebase's set() throws if the object contains an `undefined` value —
  // omit blank fields entirely rather than passing them as undefined.
  const patch: DeviceSettings = {};
  const width = Number(settingsWidth.value);
  const height = Number(settingsHeight.value);
  const frameRate = Number(settingsFps.value);
  if (width > 0) patch.width = width;
  if (height > 0) patch.height = height;
  if (frameRate > 0) patch.frameRate = frameRate;
  setDeviceSettings(settingsTargetDeviceId, patch);
  closeSettingsModal();
};
settingsModal.onclick = (e) => {
  if (e.target === settingsModal) closeSettingsModal();
};

async function main() {
  await initSignaling();
  listenDevices((list) => {
    devices = list.sort((a, b) => a.name.localeCompare(b.name));
    renderList();
  });
}

main().catch((err) => console.error('client failed to start', err));
