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
