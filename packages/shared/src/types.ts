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
