import { app } from 'electron';
import { randomUUID, createHash } from 'node:crypto';
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { hostname, platform } from 'node:os';
import { execFileSync } from 'node:child_process';

interface LocalConfig {
  deviceId: string;
  name: string;
}

const configPath = () => join(app.getPath('userData'), 'config.local.json');

/**
 * A stable identifier for the physical machine itself — not tied to this
 * install (survives redownloading/reinstalling the agent, unlike a random
 * ID cached in userData) and not tied to IP (which changes). Windows'
 * MachineGuid and Linux's /etc/machine-id are both readable by a normal
 * user, no admin/root needed, and are unique per OS installation.
 */
function readMachineCharacteristic(): string | null {
  try {
    if (platform() === 'win32') {
      const out = execFileSync(
        'reg',
        ['query', 'HKLM\\SOFTWARE\\Microsoft\\Cryptography', '/v', 'MachineGuid'],
        { encoding: 'utf-8' }
      );
      const match = out.match(/MachineGuid\s+REG_SZ\s+(\S+)/i);
      return match ? match[1] : null;
    }
    if (platform() === 'linux') {
      for (const path of ['/etc/machine-id', '/var/lib/dbus/machine-id']) {
        if (existsSync(path)) return readFileSync(path, 'utf-8').trim();
      }
    }
  } catch {
    // fall through to the random-id fallback below
  }
  return null;
}

/**
 * Persistent per-machine identity. Derived from a stable OS-level machine
 * identifier when available, so re-downloading/reinstalling the agent (or
 * running it from a fresh copy of the portable exe) still resolves to the
 * same Firebase device record instead of registering as a new duplicate.
 * Falls back to a random ID cached in userData if no such characteristic
 * could be read — still stable across restarts, just not across reinstalls.
 */
export function loadOrCreateLocalConfig(): LocalConfig {
  const machineCharacteristic = readMachineCharacteristic();
  if (machineCharacteristic) {
    const deviceId = createHash('sha256').update(`${platform()}:${machineCharacteristic}`).digest('hex').slice(0, 32);
    const p = configPath();
    const existingName = existsSync(p) ? (JSON.parse(readFileSync(p, 'utf-8')) as LocalConfig).name : hostname();
    const cfg: LocalConfig = { deviceId, name: existingName };
    writeFileSync(p, JSON.stringify(cfg, null, 2));
    return cfg;
  }

  // Fallback: cached random UUID, same as before this machine-id support existed.
  const p = configPath();
  if (existsSync(p)) {
    return JSON.parse(readFileSync(p, 'utf-8')) as LocalConfig;
  }
  const cfg: LocalConfig = { deviceId: randomUUID(), name: hostname() };
  writeFileSync(p, JSON.stringify(cfg, null, 2));
  return cfg;
}
