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
});

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
