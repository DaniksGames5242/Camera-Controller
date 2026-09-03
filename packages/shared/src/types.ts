export type Platform = 'windows' | 'linux' | 'android';

export interface DeviceRecord {
  name: string;
  platform: Platform;
  status: 'online' | 'offline';
  lastSeen: number; // ms epoch, updated by heartbeat
}

export interface DeviceWithId extends DeviceRecord {
  id: string;
}

/**
 * A device's heartbeat is every 20s (see registerDevice). status:'online'
 * alone isn't trustworthy on its own: it only flips to 'offline' via
 * onDisconnect, which fires when Firebase's server notices the socket
 * dropped — that can lag well behind the agent actually being gone
 * (uninstalled, force-killed, network cut), leaving a device that shows
 * "online" forever with nothing really there. Treat a stale heartbeat as
 * offline regardless of the stored status.
 */
const STALE_ONLINE_MS = 60_000;

export function isDeviceOnline(device: Pick<DeviceRecord, 'status' | 'lastSeen'>): boolean {
  return device.status === 'online' && Date.now() - device.lastSeen < STALE_ONLINE_MS;
}

export interface SessionDescriptionPayload {
  type: RTCSdpType;
  sdp: string;
}

export interface IceCandidatePayload {
  candidate: string;
  sdpMid: string | null;
  sdpMLineIndex: number | null;
}

/**
 * A "call" is one viewing session: client (caller) -> agent (callee).
 * Stored at /rooms/{roomId}/calls/{deviceId}/{callId}
 */
export interface CallNode {
  offer?: SessionDescriptionPayload;
  answer?: SessionDescriptionPayload;
  createdAt: number;
  status: 'pending' | 'accepted' | 'ended';
}

/**
 * Per-device capture preferences set from a client, read by that device's
 * own agent before it starts capturing for a new call. Absent fields (or an
 * absent record entirely) mean "use the agent's own default".
 * Stored at /rooms/{roomId}/deviceSettings/{deviceId}
 */
export interface DeviceSettings {
  width?: number;
  height?: number;
  frameRate?: number;
}
