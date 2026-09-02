import { app, BrowserWindow, ipcMain, session } from 'electron';
import { join } from 'node:path';
import { ensureAutostart } from './autostart.js';
import { loadOrCreateLocalConfig } from './deviceId.js';

// Agent has no UI of its own — it's a background camera/mic source that only
// becomes active while a client is actively viewing it.
app.disableHardwareAcceleration();

if (!app.requestSingleInstanceLock()) {
  app.quit();
}

let win: BrowserWindow | null = null;

app.whenReady().then(() => {
  ensureAutostart();

  // Headless: no user is present to click an "Allow camera access" dialog,
  // so we grant our own renderer's media requests automatically. Nothing
  // else runs in this app, so this can't be abused by untrusted content.
  session.defaultSession.setPermissionRequestHandler((_wc, permission, callback) => {
    callback(permission === 'media');
  });

  const localConfig = loadOrCreateLocalConfig();
  ipcMain.handle('get-device-info', () => ({
    id: localConfig.deviceId,
    name: localConfig.name,
    platform: process.platform === 'win32' ? 'windows' : 'linux',
  }));

  win = new BrowserWindow({
    show: false,
    webPreferences: {
      preload: join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
    },
  });
  win.loadFile(join(__dirname, 'renderer', 'index.html'));
});

app.on('window-all-closed', () => {
  // Keep running in the background — this app IS the background service.
});
