import { app } from 'electron';
import { platform } from 'node:os';
import { existsSync, mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const LINUX_DESKTOP_FILE_NAME = 'mycamerascontroller-agent.desktop';

/**
 * Registers this app to start on login, per-user, no elevation required.
 * Windows: Electron's built-in login-item API writes to HKCU\...\Run.
 * Linux: XDG autostart entry under ~/.config/autostart — respected by every
 * major desktop environment without touching anything system-wide.
 * Safe to call on every launch; both paths are idempotent.
 */
export function ensureAutostart(): void {
  const plat = platform();

  if (plat === 'win32') {
    app.setLoginItemSettings({ openAtLogin: true, path: process.execPath });
    return;
  }

  if (plat === 'linux') {
    const autostartDir = join(app.getPath('home'), '.config', 'autostart');
    const target = join(autostartDir, LINUX_DESKTOP_FILE_NAME);
    if (existsSync(target)) return; // already registered

    mkdirSync(autostartDir, { recursive: true });
    const execPath = process.env.APPIMAGE ?? process.execPath;
    const desktopEntry = [
      '[Desktop Entry]',
      'Type=Application',
      'Name=MyCamerasController Agent',
      `Exec="${execPath}" --hidden`,
      'X-GNOME-Autostart-enabled=true',
      'NoDisplay=true',
      'Terminal=false',
    ].join('\n');
    writeFileSync(target, desktopEntry, { mode: 0o755 });
    return;
  }

  // Other platforms (agent isn't shipped for them) — nothing to do.
}
