export interface Env {
  TURN_KEY_ID: string;
  TURN_KEY_API_TOKEN: string;
  ALLOWED_ROOM_ID: string;
}

// How long a minted credential stays valid. Fetched fresh per call rather
// than cached, so this only needs to comfortably outlast one viewing
// session — 24h covers even a long-running open viewer with room to spare,
// well under Cloudflare's 172800s (48h) max.
const CREDENTIAL_TTL_SECONDS = 86_400;

const CORS_HEADERS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, OPTIONS',
};

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    if (request.method === 'OPTIONS') {
      return new Response(null, { headers: CORS_HEADERS });
    }

    // Anyone who finds this URL could otherwise mint credentials against
    // this Cloudflare account's quota — gate it behind the same ROOM_ID
    // every client/agent already needs to join the Firebase room, matching
    // the project's existing security posture rather than adding a new one.
    const room = new URL(request.url).searchParams.get('room');
    if (room !== env.ALLOWED_ROOM_ID) {
      return new Response('forbidden', { status: 403, headers: CORS_HEADERS });
    }

    const resp = await fetch(
      `https://rtc.live.cloudflare.com/v1/turn/keys/${env.TURN_KEY_ID}/credentials/generate-ice-servers`,
      {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${env.TURN_KEY_API_TOKEN}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ ttl: CREDENTIAL_TTL_SECONDS }),
      },
    );

    if (!resp.ok) {
      return new Response('turn credential generation failed', { status: 502, headers: CORS_HEADERS });
    }

    const body = await resp.text();
    return new Response(body, {
      headers: { ...CORS_HEADERS, 'Content-Type': 'application/json' },
    });
  },
};
