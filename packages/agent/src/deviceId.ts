import { app } from 'electron';
import { randomUUID } from 'node:crypto';
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { hostname } from 'node:os';

interface LocalConfig {
  deviceId: string;
  name: string;
}

const configPath = () => join(app.getPath('userData'), 'config.local.json');

/** Persistent per-install identity: generated once, reused across restarts. */
export function loadOrCreateLocalConfig(): LocalConfig {
  const p = configPath();
  if (existsSync(p)) {
    return JSON.parse(readFileSync(p, 'utf-8')) as LocalConfig;
  }
  const cfg: LocalConfig = { deviceId: randomUUID(), name: hostname() };
  writeFileSync(p, JSON.stringify(cfg, null, 2));
  return cfg;
}
