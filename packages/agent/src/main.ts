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

  // WebRTC by default gathers a host candidate for every network adapter —
  // including VPN tunnel interfaces (Tailscale, Teredo, etc.) that a remote
  // viewer can never actually reach. Diagnosed live against this exact
  // agent: a viewer's call sat at iceConnectionState=checking forever, 0
  // bytes ever received, its candidate list flooded with exactly this kind
  // of dead-end address. Restrict gathering to the machine's actual
  // default-route interface (still both its private and public/STUN
  // address) so ICE spends its attempts on pairs that can actually connect.
  win.webContents.setWebRTCIPHandlingPolicy('default_public_and_private_interfaces');

  // Fired by the renderer when this device's Firebase record has been
  // deleted from a client — i.e. someone explicitly removed this agent.
  // Quitting is the actual "stop working" the removal implies; autostart
  // (or a manual relaunch) re-registers it fresh next time it's run.
  ipcMain.on('quit-app', () => app.quit());

  // Temporary: surface renderer diagnostics on stdout for debugging a
  // black-video-on-remote-side report.
  ipcMain.on('agent-log', (_e, msg: string) => console.log('[renderer]', msg));
});

app.on('window-all-closed', () => {
  // Keep running in the background — this app IS the background service.
});
