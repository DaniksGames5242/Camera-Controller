import { initializeApp, type FirebaseApp } from 'firebase/app';
import { getAuth, signInAnonymously, type Auth } from 'firebase/auth';
import {
  getDatabase,
  ref,
  set,
  update,
  remove,
  push,
  onValue,
  onChildAdded,
  onDisconnect,
  serverTimestamp,
  type Database,
  type Unsubscribe,
} from 'firebase/database';
import { firebaseConfig } from './firebaseConfig.js';
import type {
  DeviceRecord,
  DeviceWithId,
  SessionDescriptionPayload,
  IceCandidatePayload,
} from './types.js';

let app: FirebaseApp | null = null;
let db: Database | null = null;
let auth: Auth | null = null;

/** Call once per process before using anything else in this module. */
export async function initSignaling(): Promise<Database> {
  if (db) return db;
  app = initializeApp(firebaseConfig);
  db = getDatabase(app);
  auth = getAuth(app);
  await signInAnonymously(auth);
  return db;
}

const roomId = () => firebaseConfig.roomId;

// ---------- devices ----------

function deviceRef(deviceId: string) {
  return ref(db!, `rooms/${roomId()}/devices/${deviceId}`);
}

function devicesRef() {
  return ref(db!, `rooms/${roomId()}/devices`);
}

/**
 * Registers this device as online, arranges for it to flip to 'offline'
 * automatically if the process dies (onDisconnect), and keeps lastSeen fresh.
 * Returns a stop() function and the heartbeat interval handle.
 */
export function registerDevice(
  deviceId: string,
  info: Omit<DeviceRecord, 'status' | 'lastSeen'>
): { stop: () => void } {
  const r = deviceRef(deviceId);
  const online: DeviceRecord = { ...info, status: 'online', lastSeen: Date.now() };
  set(r, online);
  onDisconnect(r).update({ status: 'offline', lastSeen: serverTimestamp() });

  const heartbeat = setInterval(() => {
    update(r, { lastSeen: Date.now(), status: 'online' });
  }, 20_000);

  return {
    stop: () => {
      clearInterval(heartbeat);
      update(r, { status: 'offline', lastSeen: Date.now() });
    },
  };
}

export function listenDevices(cb: (devices: DeviceWithId[]) => void): Unsubscribe {
  return onValue(devicesRef(), (snap) => {
    const val = (snap.val() ?? {}) as Record<string, DeviceRecord>;
    const list: DeviceWithId[] = Object.entries(val).map(([id, d]) => ({ id, ...d }));
    cb(list);
  });
}

/**
 * Forgets a device record outright — e.g. one left behind by a reinstalled
 * or decommissioned agent. A device that comes back online afterwards just
 * re-registers itself (registerDevice writes with set(), not update()).
 */
export function forgetDevice(deviceId: string) {
  remove(deviceRef(deviceId));
}

// ---------- calls (one viewing session) ----------

type CallRole = 'caller' | 'callee';

function callsForDeviceRef(deviceId: string) {
  return ref(db!, `rooms/${roomId()}/calls/${deviceId}`);
}

function callRef(deviceId: string, callId: string) {
  return ref(db!, `rooms/${roomId()}/calls/${deviceId}/${callId}`);
}

function candidatesRef(deviceId: string, callId: string, role: CallRole) {
  return ref(db!, `rooms/${roomId()}/calls/${deviceId}/${callId}/candidates/${role}`);
}

/** Client side: start a new viewing session against a target device. */
export function createCall(targetDeviceId: string): string {
  const r = push(callsForDeviceRef(targetDeviceId));
  set(r, { createdAt: Date.now(), status: 'pending' });
  return r.key as string;
}

export function sendOffer(targetDeviceId: string, callId: string, offer: SessionDescriptionPayload) {
  update(callRef(targetDeviceId, callId), { offer });
}

export function sendAnswer(targetDeviceId: string, callId: string, answer: SessionDescriptionPayload) {
  update(callRef(targetDeviceId, callId), { answer, status: 'accepted' });
}

export function sendIceCandidate(
  targetDeviceId: string,
  callId: string,
  role: CallRole,
  candidate: IceCandidatePayload
) {
  push(candidatesRef(targetDeviceId, callId, role), candidate);
}

export function onRemoteIceCandidates(
  targetDeviceId: string,
  callId: string,
  remoteRole: CallRole,
  cb: (candidate: IceCandidatePayload) => void
): Unsubscribe {
  return onChildAdded(candidatesRef(targetDeviceId, callId, remoteRole), (snap) => {
    cb(snap.val() as IceCandidatePayload);
  });
}

/**
 * Agent side (callee): fires once per new incoming call for this device.
 * The call node is created before its offer is written (createCall vs.
 * sendOffer are separate writes), so this can't just watch for the child
 * being added — it has to watch each new call node's value until an offer
 * shows up, which may already be there or may land moments later.
 */
export function onIncomingCall(
  myDeviceId: string,
  cb: (callId: string, offer: SessionDescriptionPayload) => void
): Unsubscribe {
  const handled = new Set<string>();
  const perCallUnsub = new Map<string, Unsubscribe>();

  const addedUnsub = onChildAdded(callsForDeviceRef(myDeviceId), (snap) => {
    const callId = snap.key as string;
    if (perCallUnsub.has(callId)) return;
    const stop = onValue(callRef(myDeviceId, callId), (s) => {
      const val = s.val();
      if (val?.offer && !handled.has(callId)) {
        handled.add(callId);
        cb(callId, val.offer as SessionDescriptionPayload);
      }
    });
    perCallUnsub.set(callId, stop);
  });

  return () => {
    addedUnsub();
    perCallUnsub.forEach((stop) => stop());
  };
}

/** Either side: watch for the other side setting an answer. */
export function onAnswerSet(
  targetDeviceId: string,
  callId: string,
  cb: (answer: SessionDescriptionPayload) => void
): Unsubscribe {
  return onValue(callRef(targetDeviceId, callId), (snap) => {
    const val = snap.val();
    if (val?.answer) cb(val.answer as SessionDescriptionPayload);
  });
}

/** Either side: watch for the call node being deleted (hangup) by the peer. */
export function onCallEnded(targetDeviceId: string, callId: string, cb: () => void): Unsubscribe {
  return onValue(callRef(targetDeviceId, callId), (snap) => {
    if (!snap.exists()) cb();
  });
}

/** Client hangs up by deleting the call node entirely — agent's onCallEnded fires. */
export function endCall(targetDeviceId: string, callId: string) {
  remove(callRef(targetDeviceId, callId));
}
