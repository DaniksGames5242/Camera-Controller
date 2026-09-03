// STUN: public Google servers, used first for direct P2P.
// TURN: Open Relay Project public free relay — fallback when direct P2P fails (e.g. CGNAT).
// These credentials are intentionally public (published by the Open Relay Project itself).
// Swap for a paid TURN provider later if reliability under load becomes an issue.
export const ICE_SERVERS: RTCIceServer[] = [
  { urls: 'stun:stun.l.google.com:19302' },
  {
    urls: 'turn:openrelay.metered.ca:80',
    username: 'openrelayproject',
    credential: 'openrelayproject',
  },
  {
    urls: 'turn:openrelay.metered.ca:443',
    username: 'openrelayproject',
    credential: 'openrelayproject',
  },
  {
    urls: 'turn:openrelay.metered.ca:443?transport=tcp',
    username: 'openrelayproject',
    credential: 'openrelayproject',
  },
  // Real TLS on 443 (confirmed: the server presents a valid cert for
  // *.relay.metered.ca there) — carrier/router deep packet inspection that
  // resets the plain-TCP TURN candidate above because it doesn't look like
  // HTTPS generally lets this one through, since it's indistinguishable
  // from an ordinary HTTPS connection.
  {
    urls: 'turns:openrelay.metered.ca:443?transport=tcp',
    username: 'openrelayproject',
    credential: 'openrelayproject',
  },
];

export function createPeerConnection(): RTCPeerConnection {
  return new RTCPeerConnection({ iceServers: ICE_SERVERS });
}
