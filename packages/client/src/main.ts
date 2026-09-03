import { app, BrowserWindow, ipcMain } from 'electron';
import { join } from 'node:path';
import { createWriteStream, existsSync, mkdirSync, renameSync, type WriteStream } from 'node:fs';
import { randomUUID } from 'node:crypto';

let win: BrowserWindow | null = null;

app.whenReady().then(() => {
  win = new BrowserWindow({
    width: 1100,
    height: 720,
    webPreferences: {
      preload: join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      // This is a surveillance app: recordings must keep advancing while
      // the window is minimized or behind another one, so the renderer
      // (and the <video> elements decoding every open camera) can't be
      // throttled just because it isn't currently visible.
      backgroundThrottling: false,
    },
  });
  win.loadFile(join(__dirname, 'renderer', 'index.html'));

  // Ctrl+wheel (and pinch-to-zoom on a trackpad) otherwise triggers
  // Chromium's own page zoom — independent of, and invisible to, the
  // canvas's own 'wheel' listener that steps through view modes. That
  // reads as the whole window suddenly ballooning to fill the screen.
  // This app has no use for page zoom, so pin it at 1x.
  win.webContents.setVisualZoomLevelLimits(1, 1);

  // WebRTC by default gathers a host candidate for every network adapter,
  // including dead ends a peer on another machine can never actually reach:
  // VPN tunnel interfaces (Tailscale's 100.64.0.0/10 and fd7a:115c:a1e0::/48
  // ranges), Windows' built-in Teredo IPv6 tunnel, etc. Diagnosed live: a
  // call to an Android agent sat at iceConnectionState=checking forever,
  // 0 bytes received, its candidate list flooded with exactly these — the
  // real LAN/STUN pair never got a chance. Restricting gathering to the
  // addresses of the machine's actual default-route interface (still both
  // its private and public/STUN address) cuts that noise out.
  win.webContents.setWebRTCIPHandlingPolicy('default_public_and_private_interfaces');
});

// Temporary: surface renderer diagnostics on stdout for debugging an
// Android-agent-video-not-decoding report.
ipcMain.on('diag-log', (_e, msg: string) => console.log('[renderer]', msg));

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});

// ---------- per-viewing-session recording ----------
// Every viewing session is recorded to disk automatically, start to finish,
// so there's a local record of exactly when a given camera was watched.

function sanitizeForFilename(s: string): string {
  return s.replace(/[^a-zA-Z0-9а-яА-ЯёЁ_-]+/g, '_');
}

function recordingsDir(): string {
  const dir = join(app.getPath('videos'), 'MyCamerasController');
  if (!existsSync(dir)) mkdirSync(dir, { recursive: true });
  return dir;
}

interface OpenRecording {
  stream: WriteStream;
  partPath: string;
  deviceName: string;
  startIso: string;
}
const openRecordings = new Map<string, OpenRecording>();

ipcMain.handle('recording-start', (_e, deviceName: string, startIso: string) => {
  const recordingId = randomUUID();
  const partPath = join(
    recordingsDir(),
    `${sanitizeForFilename(deviceName)}_${sanitizeForFilename(startIso)}.webm.part`
  );
  openRecordings.set(recordingId, {
    stream: createWriteStream(partPath),
    partPath,
    deviceName,
    startIso,
  });
  return recordingId;
});

ipcMain.on('recording-chunk', (_e, recordingId: string, chunk: ArrayBuffer) => {
  openRecordings.get(recordingId)?.stream.write(Buffer.from(chunk));
});

ipcMain.on('recording-finish', (_e, recordingId: string, endIso: string) => {
  const rec = openRecordings.get(recordingId);
  if (!rec) return;
  openRecordings.delete(recordingId);
  rec.stream.end(() => {
    const finalPath = join(
      recordingsDir(),
      `${sanitizeForFilename(rec.deviceName)}_${sanitizeForFilename(rec.startIso)}_to_${sanitizeForFilename(endIso)}.webm`
    );
    try {
      renameSync(rec.partPath, finalPath);
    } catch {
      // best-effort — the .part file is still a valid, playable recording either way
    }
  });
});
