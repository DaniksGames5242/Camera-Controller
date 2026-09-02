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

import { HoloStage } from './holo/stage.js';
import type { Panel } from './holo/panels.js';
import { projectPanel } from './holo/project.js';
import { BootSequence } from './holo/ui/boot.js';
import { HoloCursor } from './holo/ui/cursor.js';
import { CommandPalette, HoloConfirm, RadialMenu, ToastStack, type Command } from './holo/ui/overlays.js';
import { Damper, ScrambleText, Spring, ticker } from './holo/ui/motion.js';
import { clamp, lerp } from './holo/math.js';

declare global {
  interface Window {
    mcc: {
      startRecording: (deviceName: string, startIso: string) => Promise<string>;
      writeRecordingChunk: (recordingId: string, chunk: ArrayBuffer) => void;
      finishRecording: (recordingId: string, endIso: string) => void;
    };
  }
}

// ---------------------------------------------------------------------------
// Shell
// ---------------------------------------------------------------------------

const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

const canvas = document.getElementById('stage-gl') as HTMLCanvasElement;
const slabLayer = document.getElementById('slab-layer') as HTMLElement;
const listEl = document.getElementById('device-list') as HTMLElement;
const emptyEl = document.getElementById('device-empty') as HTMLElement;
const stageEmptyEl = document.getElementById('stage-empty') as HTMLElement;
const stageTitleText = document.getElementById('stage-title-text') as HTMLElement;
const brandMark = document.getElementById('brand-mark') as HTMLElement;

const statNodes = document.getElementById('stat-nodes') as HTMLElement;
const statOnline = document.getElementById('stat-online') as HTMLElement;
const statChannels = document.getElementById('stat-channels') as HTMLElement;
const dockMeterFill = document.querySelector('#dock-meter .dock-meter-fill') as HTMLElement;

/** Hidden host for the <video> elements that feed the slab textures. */
const videoVault = document.createElement('div');
videoVault.id = 'video-vault';
document.body.appendChild(videoVault);

const stage = new HoloStage({ canvas, reduceMotion });
const cursor = reduceMotion ? null : new HoloCursor(document.body);
const palette = new CommandPalette();
const toasts = new ToastStack();
const radial = new RadialMenu();
const confirmDialog = new HoloConfirm();

/** The virtual pixel grid every slab's chrome is authored in. */
const SLAB_W = 960;
const SLAB_H = 540;

type LayoutMode = 'grid' | 'arc' | 'focus';
let layoutMode: LayoutMode = 'arc';
let focusedId: string | null = null;

let devices: DeviceWithId[] = [];

// ---------------------------------------------------------------------------
// Sessions
// ---------------------------------------------------------------------------

interface Session {
  deviceId: string;
  deviceName: string;
  callId: string;
  pc: RTCPeerConnection;
  unsub: Array<() => void>;
  panel: Panel;
  chrome: SlabChrome;
  video: HTMLVideoElement;
  audioSender: RTCRtpSender;
  micTrack?: MediaStreamTrack;
  stream?: MediaStream;
  recorder?: MediaRecorder;
  recordingId?: string;
  startIso?: string;
  recordingWanted: boolean;
  recordingStartedAt: number;
  openedAt: number;
  closing: boolean;
}

const sessions = new Map<string, Session>();

function toPayload(c: RTCIceCandidate): IceCandidatePayload {
  return { candidate: c.candidate, sdpMid: c.sdpMid, sdpMLineIndex: c.sdpMLineIndex };
}

// ---------------------------------------------------------------------------
// Slab chrome — DOM welded onto a 3D holographic plate
// ---------------------------------------------------------------------------

interface SlabChrome {
  root: HTMLElement;
  name: ScrambleText;
  clock: HTMLElement;
  resolution: HTMLElement;
  state: HTMLElement;
  recBtn: HTMLButtonElement;
  micBtn: HTMLButtonElement;
  focusBtn: HTMLButtonElement;
  closeBtn: HTMLButtonElement;
  dot: HTMLElement;
}

function buildSlabChrome(deviceName: string): SlabChrome {
  const root = document.createElement('div');
  root.className = 'slab';
  root.style.width = `${SLAB_W}px`;
  root.style.height = `${SLAB_H}px`;

  const top = document.createElement('div');
  top.className = 'slab-top';
  const dot = document.createElement('i');
  dot.className = 'slab-dot';
  const nameEl = document.createElement('span');
  nameEl.className = 'slab-name';
  const state = document.createElement('span');
  state.className = 'slab-state';
  state.textContent = 'СОГЛАСОВАНИЕ';
  top.append(dot, nameEl, state);

  const meta = document.createElement('div');
  meta.className = 'slab-meta';
  const resolution = document.createElement('span');
  resolution.textContent = '—';
  const clock = document.createElement('span');
  clock.className = 'slab-clock';
  clock.textContent = '00:00';
  meta.append(resolution, clock);

  const actions = document.createElement('div');
  actions.className = 'slab-actions';
  const mk = (cls: string, label: string, hint: string) => {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = `slab-btn ${cls}`;
    b.innerHTML = `<i></i><span>${label}</span>`;
    b.title = hint;
    return b;
  };
  const recBtn = mk('rec active', 'ЗАПИСЬ', 'Остановить или возобновить запись этого канала (R)');
  const micBtn = mk('mic', 'МИКРОФОН', 'Передавать звук на устройство (M)');
  const focusBtn = mk('focus', 'ФОКУС', 'Развернуть канал на всё поле');
  const closeBtn = mk('close', 'ЗАКРЫТЬ', 'Закрыть канал (Esc)');
  actions.append(recBtn, micBtn, focusBtn, closeBtn);

  root.append(top, meta, actions);
  slabLayer.appendChild(root);

  const name = new ScrambleText(nameEl, 1.6);
  name.set(deviceName);

  return { root, name, clock, resolution, state, recBtn, micBtn, focusBtn, closeBtn, dot };
}

// ---------------------------------------------------------------------------
// Layout — where the slabs live in the room
// ---------------------------------------------------------------------------

/**
 * The working volume, measured on whichever depth plane the caller cares
 * about. Sizing anything from the z = 0 plane and then placing it nearer the
 * camera is how a panel ends up half off the screen — the frustum is a
 * pyramid, not a box.
 */
function stageBounds(z = 0) {
  const { halfWidth, halfHeight } = stage.camera.visibleHalfSize(z);
  const railPx = 336;
  const railFraction = clamp(railPx / Math.max(1, window.innerWidth), 0, 0.6);
  // Offset the working volume to the right of the rail and leave headroom
  // for the title above and the dock below.
  return {
    centerX: halfWidth * railFraction * 0.9,
    halfW: halfWidth * (1 - railFraction) * 0.94,
    halfH: halfHeight * 0.80,
    centerY: 0.25,
  };
}

function relayout() {
  const list = [...sessions.values()];
  const n = list.length;
  stageEmptyEl.classList.toggle('hidden', n > 0);
  if (n === 0) {
    stageTitleText.textContent = '';
    return;
  }

  const bounds = stageBounds();
  const focusIndex = focusedId ? list.findIndex((s) => s.deviceId === focusedId) : -1;
  const focusMode = layoutMode === 'focus' && focusIndex >= 0;

  if (focusMode) {
    // One slab front and centre, the rest docked along the bottom as a
    // shallow ribbon — still live, still one click away.
    list.forEach((session, i) => {
      const panel = session.panel;
      if (i === focusIndex) {
        const w = Math.min(bounds.halfW * 2 * 0.98, bounds.halfH * 2 * 0.92 * (16 / 9));
        panel.tWidth = w;
        panel.tHeight = w * (9 / 16);
        panel.tx = bounds.centerX;
        panel.ty = bounds.centerY + bounds.halfH * 0.16;
        panel.tz = 1.4;
        panel.tRotX = 0;
        panel.tRotY = 0;
        panel.tRotZ = 0;
        panel.tFocus = 1;
      } else {
        const others = list.filter((_, j) => j !== focusIndex);
        const k = others.indexOf(session);
        const spread = Math.min(others.length, 6);
        const w = bounds.halfW * 2 / Math.max(4, spread + 1) * 0.9;
        panel.tWidth = w;
        panel.tHeight = w * (9 / 16);
        panel.tx = bounds.centerX + (k - (others.length - 1) / 2) * (w * 1.12);
        panel.ty = bounds.centerY - bounds.halfH * 0.86;
        panel.tz = -2.2;
        panel.tRotX = 0.22;
        panel.tRotY = 0;
        panel.tRotZ = 0;
        panel.tFocus = 0;
      }
    });
    const focused = list[focusIndex];
    stageTitleText.textContent = `КАНАЛ · ${focused.deviceName}`;
    return;
  }

  const cols = Math.max(1, Math.ceil(Math.sqrt(n)));
  const rows = Math.max(1, Math.ceil(n / cols));
  const cellW = (bounds.halfW * 2) / cols;
  const cellH = (bounds.halfH * 2) / rows;
  // Fit a 16:9 slab inside the cell with a margin, so the grid never
  // distorts the picture to fill space.
  const slabW = Math.min(cellW * 0.90, cellH * 0.90 * (16 / 9));
  const slabH = slabW * (9 / 16);

  list.forEach((session, i) => {
    const col = i % cols;
    const row = Math.floor(i / cols);
    const rowCount = Math.min(cols, n - row * cols);
    const x = bounds.centerX + (col - (rowCount - 1) / 2) * cellW;
    const y = bounds.centerY - (row - (rows - 1) / 2) * cellH;

    const panel = session.panel;
    panel.tWidth = slabW;
    panel.tHeight = slabH;
    panel.tx = x;
    panel.ty = y;

    if (layoutMode === 'arc') {
      // Wrap the wall onto a cylinder centred on the camera: every slab
      // turns to face the viewer, which is both prettier and easier to read
      // than a flat wall seen off-axis.
      const offset = x - bounds.centerX;
      panel.tz = -Math.abs(offset) * 0.22 - Math.abs(y) * 0.07;
      panel.tRotY = -offset * 0.055;
      panel.tRotX = y * 0.05;
      panel.tRotZ = 0;
    } else {
      panel.tz = 0;
      panel.tRotY = 0;
      panel.tRotX = 0;
      panel.tRotZ = 0;
    }
    panel.tFocus = session.deviceId === focusedId ? 1 : 0;
  });

  stageTitleText.textContent = `АКТИВНЫХ КАНАЛОВ · ${n}`;
}

// ---------------------------------------------------------------------------
// Session lifecycle
// ---------------------------------------------------------------------------

function closeSession(deviceId: string) {
  const session = sessions.get(deviceId);
  if (!session || session.closing) return;
  session.closing = true;

  stopRecording(session);
  session.micTrack?.stop();
  session.unsub.forEach((u) => u());
  session.pc.close();
  endCall(session.deviceId, session.callId);

  // Scatter the plate back into the dust it came from, then remove it.
  session.panel.tMaterialize = 0;
  session.panel.glitch = 0.9;
  stage.interaction.burst(session.panel.x, session.panel.y, session.panel.z, 1.1, 0.6);
  stage.interaction.kickGlitch(0.5);
  session.chrome.root.classList.add('leaving');

  sessions.delete(deviceId);
  if (focusedId === deviceId) {
    focusedId = null;
    if (layoutMode === 'focus') layoutMode = 'arc';
  }

  window.setTimeout(() => {
    stage.removePanel(session.panel);
    session.chrome.root.remove();
    session.video.srcObject = null;
    session.video.remove();
  }, 900);

  relayout();
  renderList();
  refreshCommands();
  toasts.push(`Канал «${session.deviceName}» закрыт`, 'info', 2600);
}

async function openViewer(deviceId: string, deviceName: string) {
  if (sessions.has(deviceId)) {
    focusSession(deviceId);
    return;
  }

  const video = document.createElement('video');
  video.autoplay = true;
  video.playsInline = true;
  video.muted = true;
  videoVault.appendChild(video);

  const panel = stage.addPanel({
    id: deviceId,
    video,
    tint: tintForDevice(deviceId),
  });
  // Slabs arrive from below and behind, as if lifted out of the floor.
  panel.x = 0;
  panel.y = -6;
  panel.z = -8;
  panel.tMaterialize = 1;

  const chrome = buildSlabChrome(deviceName);
  const callId = createCall(deviceId);
  const pc = createPeerConnection();
  const unsub: Array<() => void> = [];

  const audioTransceiver = pc.addTransceiver('audio', { direction: 'sendrecv' });
  pc.addTransceiver('video', { direction: 'recvonly' });

  const session: Session = {
    deviceId,
    deviceName,
    callId,
    pc,
    unsub,
    panel,
    chrome,
    video,
    audioSender: audioTransceiver.sender,
    recordingWanted: true,
    recordingStartedAt: 0,
    openedAt: performance.now(),
    closing: false,
  };
  sessions.set(deviceId, session);

  wireChrome(session);
  relayout();
  renderList();
  refreshCommands();

  stage.interaction.burst(panel.tx, panel.ty, panel.tz, 1.3, 0.4);
  stage.interaction.ripple(panel.tx, panel.tz, 1.2);
  stage.interaction.kickGlitch(0.45);

  pc.ontrack = (e) => {
    if (e.track.kind !== 'video') return;
    video.srcObject = e.streams[0];
    video.play().catch(() => { /* autoplay of a muted element; nothing to do */ });
    session.stream = e.streams[0];
    chrome.state.textContent = 'ПРЯМАЯ ТРАНСЛЯЦИЯ';
    chrome.root.classList.add('live');
    stage.interaction.kickGlitch(0.7);
    if (!session.recorder && session.recordingWanted) startRecording(session, e.streams[0]);
  };

  let remoteDescSet = false;
  const pendingCandidates: RTCIceCandidate[] = [];

  pc.onicecandidate = (e) => {
    if (e.candidate) sendIceCandidate(deviceId, callId, 'caller', toPayload(e.candidate));
  };

  pc.onconnectionstatechange = () => {
    if (pc.connectionState === 'connected') chrome.root.classList.add('connected');
    if (['failed', 'closed'].includes(pc.connectionState)) {
      if (sessions.has(deviceId)) {
        toasts.push(`Соединение с «${deviceName}» потеряно`, 'error');
        closeSession(deviceId);
      }
    }
  };

  unsub.push(
    onAnswerSet(deviceId, callId, async (answer) => {
      if (pc.currentRemoteDescription) return;
      await pc.setRemoteDescription(answer as RTCSessionDescriptionInit);
      remoteDescSet = true;
      while (pendingCandidates.length) {
        const c = pendingCandidates.shift()!;
        await pc.addIceCandidate(c).catch((err) => console.error(err));
      }
    })
  );
  unsub.push(
    onRemoteIceCandidates(deviceId, callId, 'callee', (candidate) => {
      const iceCandidate = new RTCIceCandidate(candidate);
      if (remoteDescSet) {
        pc.addIceCandidate(iceCandidate).catch((err) => console.error(err));
      } else {
        pendingCandidates.push(iceCandidate);
      }
    })
  );

  const offer = await pc.createOffer();
  await pc.setLocalDescription(offer);
  sendOffer(deviceId, callId, { type: offer.type, sdp: offer.sdp! });
}

/** A stable hue per device, so a given camera always reads the same colour. */
function tintForDevice(deviceId: string): [number, number, number] {
  let hash = 0;
  for (let i = 0; i < deviceId.length; i++) hash = (hash * 31 + deviceId.charCodeAt(i)) >>> 0;
  // Sample a narrow arc of the cyan→violet→magenta ramp: wide enough to
  // tell channels apart, narrow enough that they still look like one system.
  const t = (hash % 1000) / 1000;
  const a: [number, number, number] = [0.20, 0.92, 1.0];
  const b: [number, number, number] = [0.62, 0.42, 1.0];
  const c: [number, number, number] = [1.0, 0.35, 0.78];
  const mix = t < 0.5
    ? a.map((v, i) => lerp(v, b[i], t * 2))
    : b.map((v, i) => lerp(v, c[i], (t - 0.5) * 2));
  return mix as [number, number, number];
}

function wireChrome(session: Session) {
  const { chrome, deviceId } = session;
  chrome.recBtn.onclick = () => toggleRecording(session);
  chrome.micBtn.onclick = () => toggleMic(session);
  chrome.focusBtn.onclick = () => focusSession(deviceId);
  chrome.closeBtn.onclick = () => closeSession(deviceId);

  for (const btn of [chrome.recBtn, chrome.micBtn, chrome.focusBtn, chrome.closeBtn]) {
    registerMagnet(btn, 0.34, btn.title);
  }

  chrome.root.addEventListener('pointerenter', () => { session.panel.tFocus = 1; });
  chrome.root.addEventListener('pointerleave', () => {
    session.panel.tFocus = session.deviceId === focusedId ? 1 : 0;
  });
  // Clicking anywhere on a tile — not just its small "ФОКУС" button — brings
  // it into focus. That matters most for the docked ribbon of other open
  // channels along the bottom of focus mode, which are too small to land a
  // click on any one button reliably.
  chrome.root.addEventListener('click', (e) => {
    if ((e.target as HTMLElement).closest('.slab-btn')) return;
    focusSession(deviceId);
  });
}

function focusSession(deviceId: string) {
  if (!sessions.has(deviceId)) return;
  if (focusedId === deviceId && layoutMode === 'focus') {
    layoutMode = 'arc';
    focusedId = null;
  } else {
    focusedId = deviceId;
    layoutMode = 'focus';
  }
  wheelDistant = false;
  stage.camera.setFocus(0, 0, 16);
  stage.interaction.kickGlitch(0.35);
  relayout();
  renderList();
}

/**
 * The wheel's own notion of "how zoomed in": 0 = normal overview, 1 = the
 * same layout pulled back further so every channel is comfortably visible
 * at once, 2..N+1 = that channel focused. Derived fresh from the existing
 * focusedId/layoutMode state each call rather than stored separately, so it
 * can never drift out of sync with a focus change made by a click or a
 * number key — except the normal/distant distinction, which needs the one
 * extra bit since both present as "not focused".
 */
let wheelDistant = false;

/** Snaps yaw/pitch back to dead centre and distance to normal, keeping the
 * wheel's own step tracking in sync with it. */
function recallCamera() {
  wheelDistant = false;
  stage.camera.setFocus(0, 0, 16);
  stage.camera.addOrbit(-1e6, -1e6);
  stage.camera.addOrbit(1e6, 1e6);
}

function stepView(dir: number) {
  const sessionList = [...sessions.values()];
  const totalSteps = 2 + sessionList.length;

  let step: number;
  if (layoutMode === 'focus' && focusedId) {
    const idx = sessionList.findIndex((s) => s.deviceId === focusedId);
    step = idx >= 0 ? idx + 2 : 0;
  } else {
    step = wheelDistant ? 1 : 0;
  }
  const wasFocus = step >= 2;
  step = ((step + dir) % totalSteps + totalSteps) % totalSteps;
  const isFocus = step >= 2;

  if (!isFocus) {
    wheelDistant = step === 1;
    if (wasFocus) {
      // Leaving focus: the panels need laying back out into the overview
      // arrangement before the camera pulls back to look at them.
      layoutMode = 'arc';
      focusedId = null;
      relayout();
    }
    // Distance-only otherwise. The existing overview layout already fits
    // the normal frustum; recomputing it here would resize every panel to
    // fill the new, bigger frustum and cancel the zoomed-out look — pulling
    // the camera back while leaving the same arrangement in place is what
    // actually makes it read as smaller, with room around it.
    stage.camera.setFocus(0, 0, step === 1 ? 26 : 16);
  } else {
    wheelDistant = false;
    const target = sessionList[step - 2];
    if (!target) return;
    focusedId = target.deviceId;
    layoutMode = 'focus';
    stage.camera.setFocus(0, 0, 16);
    relayout();
  }
  stage.interaction.kickGlitch(0.2);
  renderList();
}

// ---------------------------------------------------------------- recording

function startRecording(session: Session, stream: MediaStream) {
  let recorder: MediaRecorder;
  try {
    recorder = new MediaRecorder(stream, { mimeType: 'video/webm;codecs=vp8,opus' });
  } catch (err) {
    console.error('MediaRecorder unavailable, not recording this session', err);
    toasts.push('Запись недоступна для этого канала', 'warn');
    return;
  }
  const startIso = new Date().toISOString();
  session.startIso = startIso;
  session.recorder = recorder;
  session.recordingStartedAt = performance.now();

  window.mcc.startRecording(session.deviceName, startIso).then((recordingId) => {
    session.recordingId = recordingId;
  });

  recorder.ondataavailable = async (e) => {
    if (e.data.size === 0 || !session.recordingId) return;
    window.mcc.writeRecordingChunk(session.recordingId, await e.data.arrayBuffer());
  };
  recorder.start(1000);
  session.panel.tRecording = 1;
  session.chrome.root.classList.add('recording');
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
  session.panel.tRecording = 0;
  session.chrome.root.classList.remove('recording');
}

function toggleRecording(session: Session) {
  if (session.recorder) {
    session.recordingWanted = false;
    stopRecording(session);
    session.chrome.recBtn.classList.remove('active');
    session.chrome.recBtn.querySelector('span')!.textContent = 'ЗАПИСЬ';
    toasts.push(`Запись «${session.deviceName}» остановлена`, 'warn', 2600);
  } else if (session.stream) {
    session.recordingWanted = true;
    startRecording(session, session.stream);
    session.chrome.recBtn.classList.add('active');
    session.chrome.recBtn.querySelector('span')!.textContent = 'ИДЁТ ЗАПИСЬ';
    toasts.push(`Запись «${session.deviceName}» возобновлена`, 'ok', 2600);
  }
  stage.interaction.kickGlitch(0.3);
}

async function toggleMic(session: Session) {
  const { chrome } = session;
  if (!session.micTrack) {
    let micStream: MediaStream;
    try {
      micStream = await navigator.mediaDevices.getUserMedia({ audio: true });
    } catch (err) {
      console.error('mic getUserMedia failed', err);
      toasts.push('Не удалось получить доступ к микрофону', 'error');
      return;
    }
    session.micTrack = micStream.getAudioTracks()[0];
    await session.audioSender.replaceTrack(session.micTrack);
    chrome.micBtn.classList.add('active');
    chrome.micBtn.querySelector('span')!.textContent = 'МИК ВКЛ';
    stage.interaction.burst(session.panel.x, session.panel.y, session.panel.z + 1, 0.8, 0.9);
    toasts.push(`Микрофон включён → «${session.deviceName}»`, 'ok', 2600);
    return;
  }
  session.micTrack.enabled = !session.micTrack.enabled;
  chrome.micBtn.classList.toggle('active', session.micTrack.enabled);
  chrome.micBtn.querySelector('span')!.textContent = session.micTrack.enabled ? 'МИК ВКЛ' : 'МИКРОФОН';
}

// ---------------------------------------------------------------------------
// Device rail
// ---------------------------------------------------------------------------

interface DeviceRow {
  root: HTMLElement;
  name: ScrambleText;
  status: HTMLElement;
  bars: HTMLElement[];
  tiltX: Damper;
  tiltY: Damper;
  lift: Spring;
  online: boolean;
  seed: number;
}

const rows = new Map<string, DeviceRow>();

function renderList() {
  const seen = new Set<string>();
  emptyEl.classList.toggle('hidden', devices.length > 0);

  devices.forEach((device, index) => {
    seen.add(device.id);
    let row = rows.get(device.id);
    if (!row) {
      row = buildDeviceRow(device);
      rows.set(device.id, row);
      listEl.appendChild(row.root);
      // Stagger the arrival so a batch of devices cascades in rather than
      // appearing as one block.
      row.root.style.setProperty('--enter-delay', `${Math.min(index, 12) * 45}ms`);
      requestAnimationFrame(() => row!.root.classList.add('in'));
    }
    updateDeviceRow(row, device);
    // Keep DOM order in sync with the sorted model without rebuilding.
    if (listEl.children[index] !== row.root) listEl.insertBefore(row.root, listEl.children[index] ?? null);
  });

  for (const [id, row] of rows) {
    if (seen.has(id)) continue;
    rows.delete(id);
    row.root.classList.add('leaving');
    window.setTimeout(() => row.root.remove(), 420);
  }

  const online = devices.filter((d) => d.status === 'online').length;
  statNodes.textContent = String(devices.length);
  statOnline.textContent = String(online);
  statChannels.textContent = String(sessions.size);
}

function buildDeviceRow(device: DeviceWithId): DeviceRow {
  const root = document.createElement('div');
  root.className = 'node';
  root.tabIndex = 0;
  root.dataset.id = device.id;

  const glow = document.createElement('i');
  glow.className = 'node-glow';

  const dot = document.createElement('i');
  dot.className = 'node-dot';

  const body = document.createElement('div');
  body.className = 'node-body';
  const nameEl = document.createElement('div');
  nameEl.className = 'node-name';
  const status = document.createElement('div');
  status.className = 'node-status';
  body.append(nameEl, status);

  // Twelve bars driven by a noise walk: an always-live readout that makes
  // an online node visibly *alive* rather than a static list entry.
  const meter = document.createElement('div');
  meter.className = 'node-meter';
  const bars: HTMLElement[] = [];
  for (let i = 0; i < 12; i++) {
    const bar = document.createElement('i');
    meter.appendChild(bar);
    bars.push(bar);
  }

  const gear = document.createElement('button');
  gear.type = 'button';
  gear.className = 'node-gear';
  gear.title = 'Параметры захвата (разрешение, FPS)';
  gear.textContent = '⚙';
  gear.addEventListener('click', (e) => {
    e.stopPropagation();
    openSettingsConsole(device.id, device.name);
  });
  registerMagnet(gear, 0.42, 'Параметры захвата');

  root.append(glow, dot, body, meter, gear);

  const row: DeviceRow = {
    root,
    name: new ScrambleText(nameEl, 1.4),
    status,
    bars,
    tiltX: new Damper(0, 12),
    tiltY: new Damper(0, 12),
    lift: new Spring(0, 200, 19),
    online: device.status === 'online',
    seed: Math.random() * 1000,
  };
  row.name.set(device.name);

  root.addEventListener('pointermove', (e) => {
    const rect = root.getBoundingClientRect();
    // Tilt the card toward the pointer — the card behaves like a physical
    // plate under a light, not like a rectangle with a hover colour.
    row.tiltY.to(((e.clientX - rect.left) / rect.width - 0.5) * 6);
    row.tiltX.to(-((e.clientY - rect.top) / rect.height - 0.5) * 5);
  });
  root.addEventListener('pointerenter', () => {
    row.lift.to(1);
    root.classList.add('hover');
  });
  root.addEventListener('pointerleave', () => {
    row.lift.to(0);
    row.tiltX.to(0);
    row.tiltY.to(0);
    root.classList.remove('hover');
  });
  root.addEventListener('click', () => activateRow(device.id));
  root.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); activateRow(device.id); }
  });
  root.addEventListener('contextmenu', (e) => {
    e.preventDefault();
    openNodeMenu(e.clientX, e.clientY, device.id);
  });
  registerMagnet(root, 0.16);

  return row;
}

function activateRow(deviceId: string) {
  const device = devices.find((d) => d.id === deviceId);
  if (!device) return;
  const row = rows.get(deviceId);
  row?.lift.kick(9);
  if (sessions.has(deviceId)) {
    focusSession(deviceId);
    return;
  }
  if (device.status !== 'online') {
    row?.root.classList.add('reject');
    window.setTimeout(() => row?.root.classList.remove('reject'), 500);
    toasts.push(`Узел «${device.name}» не в сети`, 'warn', 2600);
    return;
  }
  openViewer(deviceId, device.name);
}

function updateDeviceRow(row: DeviceRow, device: DeviceWithId) {
  const online = device.status === 'online';
  const open = sessions.has(device.id);
  if (row.online !== online) {
    row.online = online;
    // A node coming online is a real event on the stage, not just a colour
    // change in a list.
    if (online) {
      stage.interaction.ripple((Math.random() - 0.5) * 12, (Math.random() - 0.5) * 8, 0.7);
      toasts.push(`Узел «${device.name}» в сети`, 'ok', 3000);
    }
  }
  row.name.set(device.name);
  row.status.textContent = open ? 'КАНАЛ ОТКРЫТ' : online ? 'В СЕТИ · ГОТОВ' : 'НЕ В СЕТИ';
  row.root.classList.toggle('online', online);
  row.root.classList.toggle('open', open);
  row.root.classList.toggle('focused', focusedId === device.id);
}

function openNodeMenu(x: number, y: number, deviceId: string) {
  const device = devices.find((d) => d.id === deviceId);
  if (!device) return;
  const open = sessions.has(deviceId);
  radial.show(x, y, [
    {
      label: open ? 'Фокус' : 'Открыть',
      glyph: open ? '◎' : '▶',
      run: () => (open ? focusSession(deviceId) : activateRow(deviceId)),
    },
    { label: 'Параметры', glyph: '⚙', run: () => openSettingsConsole(deviceId, device.name) },
    ...(open ? [{ label: 'Закрыть', glyph: '✕', run: () => closeSession(deviceId) }] : []),
    {
      label: 'Забыть',
      glyph: '⌫',
      danger: true,
      run: () => confirmForget(device),
    },
  ]);
}

async function confirmForget(device: DeviceWithId) {
  const message =
    device.status === 'online'
      ? `Агент на этом устройстве остановится и не будет виден, пока его не запустят заново.`
      : `Если узел снова выйдет в сеть, он появится заново.`;
  const ok = await confirmDialog.ask(`Забыть «${device.name}»?`, message, 'ЗАБЫТЬ');
  if (!ok) return;
  if (sessions.has(device.id)) closeSession(device.id);
  forgetDevice(device.id);
  stage.interaction.kickGlitch(0.8);
  toasts.push(`Узел «${device.name}» удалён`, 'warn');
}

// ---------------------------------------------------------------------------
// Settings console — a holographic plate, not a dialog box
// ---------------------------------------------------------------------------

interface SettingsConsole {
  panel: Panel;
  root: HTMLElement;
  deviceId: string;
}

let settingsConsole: SettingsConsole | null = null;

function openSettingsConsole(deviceId: string, deviceName: string) {
  closeSettingsConsole();

  const panel = stage.addPanel({ id: `settings:${deviceId}`, tint: [0.55, 0.42, 1.0], plate: true });
  const CONSOLE_Z = 2.6;
  const CONSOLE_RATIO = 0.62;
  const bounds = stageBounds(CONSOLE_Z);
  // Fit inside the plane it actually sits on, in both axes.
  const width = Math.min(bounds.halfW * 1.9, (bounds.halfH * 1.9) / CONSOLE_RATIO);
  const vis = stage.camera.visibleHalfSize(CONSOLE_Z);
  // And keep it fully on screen even once it has been nudged clear of the rail.
  const centerX = clamp(bounds.centerX, -(vis.halfWidth - width / 2), vis.halfWidth - width / 2);

  panel.tWidth = width;
  panel.tHeight = width * CONSOLE_RATIO;
  panel.x = centerX;
  panel.y = -2.4;
  panel.z = 0.5;
  panel.tx = centerX;
  panel.ty = 0.3;
  panel.tz = CONSOLE_Z;
  panel.tFocus = 1;
  panel.tMaterialize = 1;

  // The console takes the stage: every live channel steps back and dims,
  // so the thing being edited is unambiguously the thing in front.
  for (const session of sessions.values()) {
    session.panel.tz -= 3.4;
    session.panel.tFocus = 0;
  }

  const root = document.createElement('div');
  root.className = 'console';
  root.style.width = `${SLAB_W}px`;
  root.style.height = `${Math.round(SLAB_W * 0.62)}px`;

  root.innerHTML = `
    <div class="console-head">
      <span class="console-eyebrow">ПАРАМЕТРЫ ЗАХВАТА</span>
      <span class="console-title"></span>
      <button type="button" class="console-close" title="Закрыть (Esc)">✕</button>
    </div>
    <div class="console-grid">
      <label class="field">
        <span class="field-key">ШИРИНА</span>
        <span class="field-wrap">
          <input class="field-input" id="cfg-width" type="number" min="0" step="16" placeholder="1280" />
          <span class="field-unit">px</span>
          <i class="field-line"></i>
        </span>
      </label>
      <label class="field">
        <span class="field-key">ВЫСОТА</span>
        <span class="field-wrap">
          <input class="field-input" id="cfg-height" type="number" min="0" step="16" placeholder="720" />
          <span class="field-unit">px</span>
          <i class="field-line"></i>
        </span>
      </label>
      <label class="field">
        <span class="field-key">ЧАСТОТА КАДРОВ</span>
        <span class="field-wrap">
          <input class="field-input" id="cfg-fps" type="number" min="0" max="120" step="1" placeholder="30" />
          <span class="field-unit">fps</span>
          <i class="field-line"></i>
        </span>
      </label>
    </div>
    <div class="console-presets">
      <span class="console-presets-key">ПРЕСЕТЫ</span>
      <div class="preset-row"></div>
    </div>
    <p class="console-note">
      Применяется агентом при следующем подключении — на уже открытый канал не влияет.
    </p>
    <div class="console-actions">
      <button type="button" class="console-btn ghost" id="cfg-cancel">ОТМЕНА</button>
      <button type="button" class="console-btn go" id="cfg-save">ПРИМЕНИТЬ</button>
    </div>
  `;
  slabLayer.appendChild(root);

  const title = root.querySelector('.console-title') as HTMLElement;
  new ScrambleText(title, 1.5).set(deviceName);

  const widthInput = root.querySelector('#cfg-width') as HTMLInputElement;
  const heightInput = root.querySelector('#cfg-height') as HTMLInputElement;
  const fpsInput = root.querySelector('#cfg-fps') as HTMLInputElement;

  const presets: Array<[string, number, number, number]> = [
    ['ЭКОНОМ', 640, 360, 15],
    ['БАЗА', 1280, 720, 30],
    ['ЧЁТКО', 1600, 900, 30],
    ['МАКС', 1920, 1080, 30],
  ];
  const presetRow = root.querySelector('.preset-row') as HTMLElement;
  for (const [label, w, h, fps] of presets) {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'preset';
    btn.innerHTML = `<b>${label}</b><span>${w}×${h} · ${fps}</span>`;
    btn.addEventListener('click', () => {
      widthInput.value = String(w);
      heightInput.value = String(h);
      fpsInput.value = String(fps);
      stage.interaction.burst(panel.x, panel.y, panel.z, 0.5, 0.7);
      btn.classList.add('pulse');
      window.setTimeout(() => btn.classList.remove('pulse'), 420);
    });
    registerMagnet(btn, 0.3);
    presetRow.appendChild(btn);
  }

  getDeviceSettings(deviceId).then((s) => {
    if (settingsConsole?.deviceId !== deviceId) return;
    if (s.width) widthInput.value = String(s.width);
    if (s.height) heightInput.value = String(s.height);
    if (s.frameRate) fpsInput.value = String(s.frameRate);
  });

  const save = () => {
    // Firebase's set() rejects undefined values — omit blank fields rather
    // than writing them as undefined.
    const patch: DeviceSettings = {};
    const w = Number(widthInput.value);
    const h = Number(heightInput.value);
    const fps = Number(fpsInput.value);
    if (w > 0) patch.width = w;
    if (h > 0) patch.height = h;
    if (fps > 0) patch.frameRate = fps;
    setDeviceSettings(deviceId, patch);
    toasts.push(`Параметры «${deviceName}» сохранены`, 'ok');
    stage.interaction.kickGlitch(0.5);
    closeSettingsConsole();
  };

  (root.querySelector('#cfg-save') as HTMLButtonElement).onclick = save;
  (root.querySelector('#cfg-cancel') as HTMLButtonElement).onclick = closeSettingsConsole;
  (root.querySelector('.console-close') as HTMLButtonElement).onclick = closeSettingsConsole;
  for (const btn of Array.from(root.querySelectorAll<HTMLElement>('.console-btn, .console-close'))) {
    registerMagnet(btn, 0.35);
  }
  root.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') save();
  });

  settingsConsole = { panel, root, deviceId };
  stage.interaction.burst(panel.x, panel.y, panel.z, 1.0, 0.75);
  stage.interaction.kickGlitch(0.4);
  requestAnimationFrame(() => {
    root.classList.add('in');
    widthInput.focus();
  });
}

function closeSettingsConsole() {
  const current = settingsConsole;
  if (!current) return;
  settingsConsole = null;
  // Bring the channels back to where the layout wants them.
  relayout();
  current.panel.tMaterialize = 0;
  current.panel.tz = 2.0;
  current.panel.glitch = 0.7;
  current.root.classList.remove('in');
  current.root.classList.add('out');
  stage.interaction.burst(current.panel.x, current.panel.y, current.panel.z, 0.9, 0.5);
  window.setTimeout(() => {
    stage.removePanel(current.panel);
    current.root.remove();
  }, 700);
}

// ---------------------------------------------------------------------------
// Magnetic pointer targets
// ---------------------------------------------------------------------------

function registerMagnet(el: HTMLElement, strength: number, label?: string) {
  if (!cursor) return;
  el.addEventListener('pointerenter', () => cursor.setTarget({ el, strength, label }));
  el.addEventListener('pointerleave', () => cursor.setTarget(null));
}

// ---------------------------------------------------------------------------
// Per-frame DOM ↔ stage synchronisation
// ---------------------------------------------------------------------------

function formatClock(ms: number) {
  const total = Math.max(0, Math.floor(ms / 1000));
  const m = String(Math.floor(total / 60)).padStart(2, '0');
  const s = String(total % 60).padStart(2, '0');
  return `${m}:${s}`;
}

stage.onFrame((dt, time) => {
  const w = window.innerWidth;
  const h = window.innerHeight;

  // --- slab chrome ---------------------------------------------------------
  for (const session of sessions.values()) {
    const q = projectPanel(session.panel, stage.camera, w, h, SLAB_W, SLAB_H);
    const root = session.chrome.root;
    if (!q.visible) {
      root.style.opacity = '0';
      root.style.pointerEvents = 'none';
      continue;
    }
    root.style.transform = q.matrix;
    // Fade the chrome out as the slab shrinks: unreadable text on a distant
    // plate is noise, and the slab itself still carries the picture.
    const legibility = clamp((q.area / (w * h)) * 14, 0, 1);
    // A console in front owns the stage: everything behind it steps down so
    // its own small type has nothing competing with it.
    const recessed = settingsConsole ? 0.18 : 1;
    root.style.opacity = String(clamp(session.panel.materialize * legibility * 1.4 * recessed, 0, 1));
    // Interactive whenever there's no console blocking it — even a tiny
    // docked thumbnail must stay clickable so the whole tile can be tapped
    // to bring it into focus; the per-button labels are what actually fades
    // out below the legibility threshold (via CSS opacity), not the hit area.
    root.style.pointerEvents = settingsConsole ? 'none' : 'auto';

    if (session.recorder) {
      session.chrome.clock.textContent = formatClock(performance.now() - session.recordingStartedAt);
    } else {
      session.chrome.clock.textContent = '—';
    }
    const v = session.video;
    if (v.videoWidth) {
      session.chrome.resolution.textContent = `${v.videoWidth}×${v.videoHeight}`;
    }
  }

  if (settingsConsole) {
    const consoleHeight = Math.round(SLAB_W * 0.62);
    const q = projectPanel(settingsConsole.panel, stage.camera, w, h, SLAB_W, consoleHeight);
    settingsConsole.root.style.transform = q.matrix;
    settingsConsole.root.style.opacity = String(clamp(settingsConsole.panel.materialize * 1.6, 0, 1));
  }

  // --- device rows ---------------------------------------------------------
  for (const row of rows.values()) {
    const lift = row.lift.update(dt);
    const tiltX = row.tiltX.update(dt);
    const tiltY = row.tiltY.update(dt);
    // Kept modest on purpose: this card lives inside a clipped, fixed-width
    // rail, and the lift/tilt used to be strong enough that a full hover
    // pushed the card past the rail's right edge and got visibly cropped.
    row.root.style.transform =
      `perspective(900px) translate3d(${lift * 3}px, ${-lift * 1.5}px, ${lift * 12}px) ` +
      `rotateX(${tiltX}deg) rotateY(${tiltY}deg)`;
    row.root.style.setProperty('--lift', lift.toFixed(3));

    // Signal meter: a smooth pseudo-random walk per bar. Offline nodes flatten.
    const amplitude = row.online ? 1 : 0.06;
    for (let i = 0; i < row.bars.length; i++) {
      const phase = time * 2.4 + i * 0.55 + row.seed;
      const level =
        (Math.sin(phase) * 0.5 + Math.sin(phase * 1.87 + 1.3) * 0.3 + Math.sin(phase * 3.31) * 0.2) * 0.5 + 0.5;
      const scaled = 0.12 + level * 0.88 * amplitude * (0.55 + 0.45 * lift);
      row.bars[i].style.transform = `scaleY(${scaled.toFixed(3)})`;
    }
  }

  // --- dock meter ----------------------------------------------------------
  dockMeterFill.style.transform = `scaleX(${(0.15 + stage.quality * 0.85).toFixed(3)})`;
});

// ---------------------------------------------------------------------------
// Input: pointer, wheel, drag, keyboard
// ---------------------------------------------------------------------------

function bindStageInput() {
  const toNdc = (e: PointerEvent | WheelEvent) => {
    const x = (e.clientX / window.innerWidth) * 2 - 1;
    const y = -((e.clientY / window.innerHeight) * 2 - 1);
    return { x, y };
  };

  window.addEventListener('pointermove', (e) => {
    const { x, y } = toNdc(e);
    stage.interaction.setPointer(x, y);
    if (dragging) {
      // A deadzone before the drag actually orbits the camera: an ordinary
      // click reports a pixel or two of movement from OS/mouse jitter alone,
      // and without this every click on empty space nudged the view.
      const totalDist = Math.hypot(e.clientX - dragStartX, e.clientY - dragStartY);
      if (totalDist > DRAG_THRESHOLD) {
        stage.camera.addOrbit(e.clientX - lastDragX, e.clientY - lastDragY);
      }
      lastDragX = e.clientX;
      lastDragY = e.clientY;
    }
  }, { passive: true });

  const DRAG_THRESHOLD = 4;
  let dragging = false;
  let dragStartX = 0;
  let dragStartY = 0;
  let lastDragX = 0;
  let lastDragY = 0;

  canvas.addEventListener('pointerdown', (e) => {
    if (e.button !== 0) return;
    dragging = true;
    dragStartX = lastDragX = e.clientX;
    dragStartY = lastDragY = e.clientY;
    stage.interaction.pressed = true;
    const world = stage.pointerWorld(0);
    stage.interaction.ripple(world[0], world[2], 1);
    stage.interaction.burst(world[0], world[1], world[2], 0.6, 0.5);
    canvas.setPointerCapture(e.pointerId);
  });

  window.addEventListener('pointerup', () => {
    dragging = false;
    stage.interaction.pressed = false;
  });

  // The wheel steps through view modes rather than dollying the camera:
  // normal overview → pulled-back overview (everything visible at once) →
  // each open channel in turn, one at a time. Free zoom on a hologram with
  // no real depth cues to judge distance by mostly just gets people lost.
  canvas.addEventListener('wheel', (e) => {
    e.preventDefault();
    const dir = Math.sign(e.deltaY);
    if (dir !== 0) stepView(dir);
  }, { passive: false });

  canvas.addEventListener('contextmenu', (e) => e.preventDefault());

  window.addEventListener('resize', () => {
    stage.resize();
    relayout();
  });

  window.addEventListener('keydown', (e) => {
    const typing = e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement;

    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
      e.preventDefault();
      palette.toggle();
      return;
    }
    if (typing) return;

    if (e.key === 'Escape') {
      if (settingsConsole) { closeSettingsConsole(); return; }
      if (focusedId) { closeSession(focusedId); return; }
      const last = [...sessions.keys()].pop();
      if (last) closeSession(last);
      return;
    }
    if (e.key === '/') { e.preventDefault(); palette.show(); return; }
    if (e.key >= '1' && e.key <= '9') {
      const index = Number(e.key) - 1;
      const session = [...sessions.values()][index];
      if (session) focusSession(session.deviceId);
      return;
    }
    const target = focusedId ? sessions.get(focusedId) : [...sessions.values()][0];
    if (!target) return;
    if (e.key.toLowerCase() === 'r' || e.key === 'к') toggleRecording(target);
    if (e.key.toLowerCase() === 'm' || e.key === 'ь') toggleMic(target);
    if (e.key.toLowerCase() === 'l') cycleLayout();
  });
}

function cycleLayout() {
  // Cycling always leaves focus mode: "show me everything" is the point.
  const next: LayoutMode = layoutMode === 'arc' ? 'grid' : 'arc';
  layoutMode = next;
  stage.interaction.kickGlitch(0.4);
  toasts.push(next === 'arc' ? 'Раскладка: дуга' : 'Раскладка: сетка', 'info', 2000);
  relayout();
}

// ---------------------------------------------------------------------------
// Commands
// ---------------------------------------------------------------------------

function refreshCommands() {
  const commands: Command[] = [];

  for (const device of devices) {
    const open = sessions.has(device.id);
    commands.push({
      id: `open:${device.id}`,
      group: 'УЗЛЫ',
      title: `${open ? 'Фокус' : 'Открыть'} · ${device.name}`,
      hint: device.status === 'online' ? 'в сети' : 'не в сети',
      run: () => activateRow(device.id),
    });
    commands.push({
      id: `cfg:${device.id}`,
      group: 'ПАРАМЕТРЫ',
      title: `Параметры захвата · ${device.name}`,
      hint: 'разрешение, FPS',
      run: () => openSettingsConsole(device.id, device.name),
    });
  }

  for (const session of sessions.values()) {
    commands.push({
      id: `close:${session.deviceId}`,
      group: 'КАНАЛЫ',
      title: `Закрыть канал · ${session.deviceName}`,
      run: () => closeSession(session.deviceId),
    });
    commands.push({
      id: `rec:${session.deviceId}`,
      group: 'КАНАЛЫ',
      title: `${session.recorder ? 'Остановить' : 'Начать'} запись · ${session.deviceName}`,
      run: () => toggleRecording(session),
    });
  }

  commands.push(
    { id: 'layout', group: 'ВИД', title: 'Переключить раскладку (дуга ↔ сетка)', hint: 'L', run: cycleLayout },
    { id: 'scan', group: 'ВИД', title: 'Прогнать сканирующую плоскость', run: () => stage.interaction.triggerScan() },
    {
      id: 'recall',
      group: 'ВИД',
      title: 'Вернуть камеру в исходное положение',
      run: () => recallCamera(),
    },
    {
      id: 'closeall',
      group: 'КАНАЛЫ',
      title: 'Закрыть все каналы',
      run: () => { for (const id of [...sessions.keys()]) closeSession(id); },
    }
  );

  palette.setCommands(commands);
}

// ---------------------------------------------------------------------------
// Boot
// ---------------------------------------------------------------------------

function wireDock() {
  const scan = document.getElementById('dock-scan') as HTMLButtonElement;
  const layout = document.getElementById('dock-layout') as HTMLButtonElement;
  const recall = document.getElementById('dock-recall') as HTMLButtonElement;
  const paletteBtn = document.getElementById('btn-palette') as HTMLButtonElement;
  const meter = document.getElementById('dock-meter') as HTMLElement;

  scan.onclick = () => { stage.interaction.triggerScan(); stage.interaction.kickGlitch(0.35); };
  layout.onclick = cycleLayout;
  recall.onclick = () => { recallCamera(); toasts.push('Камера возвращена', 'info', 1800); };
  paletteBtn.onclick = () => palette.toggle();

  for (const el of [scan, layout, recall, paletteBtn, meter]) {
    registerMagnet(el, 0.38, el.dataset.hint);
  }
}

async function main() {
  ticker.start();
  stage.start();

  brandMark.innerHTML = 'MYCAMS<span class="brand-slash">//</span>CTRL';
  wireDock();
  bindStageInput();
  refreshCommands();

  palette.onToggle((open) => {
    document.body.classList.toggle('palette-open', open);
    stage.interaction.kickGlitch(open ? 0.5 : 0.2);
    stage.camera.setDriftAmount(open ? 0.25 : 1);
  });

  const boot = new BootSequence(stage, reduceMotion);
  // The connection negotiates behind the boot animation rather than after
  // it: the sequence is theatre, never a gate.
  const connected = initSignaling()
    .then(() => {
      listenDevices((list) => {
        devices = list.sort((a, b) => a.name.localeCompare(b.name, 'ru'));
        renderList();
        refreshCommands();
      });
    })
    .catch((err) => {
      console.error('signaling failed', err);
      toasts.push('Не удалось подключиться к сигналингу', 'error', 8000);
    });

  await boot.run();
  await connected;
  stage.interaction.triggerScan();
}

main().catch((err) => {
  console.error('client failed to start', err);
  stage.boot = 1;
  toasts.push('Ошибка запуска интерфейса — см. консоль', 'error', 9000);
});
