# turn-worker

A tiny Cloudflare Worker that mints short-lived Cloudflare Realtime TURN
credentials on demand, so the actual API token (which must stay secret)
never ships inside the Electron/Android apps. Those apps fetch from this
worker instead of embedding TURN credentials directly.

## One-time setup

1. In the Cloudflare dashboard: **Realtime → TURN → Create a TURN Key**.
   Note the **Key ID** and **API Token** it shows you (the token is only
   shown once).
2. `npm install -g wrangler` if you don't have it, then `wrangler login`.
3. From this directory (`packages/turn-worker`):
   ```
   npm install
   wrangler secret put TURN_KEY_ID          # paste the Key ID
   wrangler secret put TURN_KEY_API_TOKEN   # paste the API Token
   wrangler secret put ALLOWED_ROOM_ID      # same value as this project's Firebase ROOM_ID
   npm run deploy
   ```
4. `wrangler deploy` prints the worker's URL
   (`https://mcc-turn-credentials.<your-subdomain>.workers.dev`). That URL
   is public (not sensitive) — plug it into `TURN_WORKER_URL` in
   `packages/shared/src/webrtc.ts` and the equivalent constant in each
   Android app's `Signaling.kt`.

## Redeploying

Only needed if you ever change `src/index.ts` itself — the secrets persist
across deploys, no need to re-set them. Just `npm run deploy` again.
