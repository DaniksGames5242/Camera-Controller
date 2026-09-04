import { firebaseConfig } from './firebaseConfig.js';

// Fill in after deploying packages/turn-worker (see its README) — the
// worker's URL is public, not sensitive, same as this file's fallback
// credentials below.
const TURN_WORKER_URL = '';

// STUN: public Google servers, used first for direct P2P.
// TURN: Open Relay Project public free relay — used only if the Cloudflare
// worker above is unreachable/not deployed yet. These credentials are
// intentionally public (published by the Open Relay Project itself).
export const FALLBACK_ICE_SERVERS: RTCIceServer[] = [
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

/**
 * Fetches short-lived TURN credentials from packages/turn-worker (Cloudflare
 * Realtime — much higher-capacity, harder-to-block infrastructure than the
 * free openrelay.metered.ca relay). Falls back to FALLBACK_ICE_SERVERS if
 * the worker isn't deployed yet, unreachable, or slow — never blocks call
 * setup on it for more than a few seconds.
 */
export async function fetchIceServers(): Promise<RTCIceServer[]> {
  if (!TURN_WORKER_URL) return FALLBACK_ICE_SERVERS;
  try {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 4000);
    const resp = await fetch(`${TURN_WORKER_URL}?room=${encodeURIComponent(firebaseConfig.roomId)}`, {
      signal: controller.signal,
    });
    clearTimeout(timeout);
    if (!resp.ok) return FALLBACK_ICE_SERVERS;
    const data = (await resp.json()) as { iceServers?: RTCIceServer[] };
    return data.iceServers?.length ? data.iceServers : FALLBACK_ICE_SERVERS;
  } catch (err) {
    console.error('fetchIceServers: falling back to static TURN list', err);
    return FALLBACK_ICE_SERVERS;
  }
}

export async function createPeerConnection(): Promise<RTCPeerConnection> {
  return new RTCPeerConnection({ iceServers: await fetchIceServers() });
}
