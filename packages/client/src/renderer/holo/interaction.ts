import { clamp, damp } from './math.js';

interface Ripple {
  x: number;
  z: number;
  born: number;
  strength: number;
}

export interface Burst {
  x: number;
  y: number;
  z: number;
  strength: number;
  hue: number;
}

/**
 * The single source of truth for "what is the user doing right now", shared
 * by every shader in the app.
 *
 * Passes never read the DOM or listen to events themselves — they read this
 * object. That keeps interaction one system instead of a dozen ad-hoc
 * listeners, and means a synthetic event (the boot sequence, an incoming
 * device, a failed connection) drives exactly the same visuals as a real
 * click.
 */
export class Interaction {
  /** Pointer in normalised device coordinates, y up. */
  ndcX = 0;
  ndcY = 0;
  /** Smoothed pointer — what the visuals actually follow. */
  smoothX = 0;
  smoothY = 0;
  /** Pointer speed, 0..1, drives the cursor's tail and field distortion. */
  speed = 0;

  /** Global excitement, 0..1. Decays; every event pushes it back up. */
  energy = 0;
  /** Short spikes that the post chain turns into datamosh glitching. */
  glitch = 0;
  /** Rising lidar plane sweeping the room, in world z. */
  scanPhase = -40;
  /** Set while a pointer button is held. */
  pressed = false;

  pressAmount = 0;

  private readonly ripples: Ripple[] = [];
  private readonly bursts: Burst[] = [];
  private lastX = 0;
  private lastY = 0;
  time = 0;

  setPointer(ndcX: number, ndcY: number) {
    this.ndcX = clamp(ndcX, -1.6, 1.6);
    this.ndcY = clamp(ndcY, -1.6, 1.6);
  }

  /** A ripple on the stage floor, in world x/z. */
  ripple(x: number, z: number, strength = 1) {
    this.ripples.push({ x, z, born: this.time, strength });
    if (this.ripples.length > 8) this.ripples.shift();
    this.energy = clamp(this.energy + 0.35 * strength, 0, 1);
  }

  /** A particle burst in world space; consumed by the particle solver. */
  burst(x: number, y: number, z: number, strength = 1, hue = 0.5) {
    this.bursts.push({ x, y, z, strength, hue });
    if (this.bursts.length > 6) this.bursts.shift();
    this.energy = clamp(this.energy + 0.3 * strength, 0, 1);
  }

  /** Kicks the post-processing glitch, e.g. when a stream connects or drops. */
  kickGlitch(amount = 1) {
    this.glitch = clamp(this.glitch + amount, 0, 1.5);
  }

  /** Restarts the lidar sweep from behind the camera. */
  triggerScan() {
    this.scanPhase = -45;
  }

  takeBursts(): Burst[] {
    if (this.bursts.length === 0) return this.bursts;
    return this.bursts.splice(0, this.bursts.length);
  }

  writeRipples(out: Float32Array) {
    out.fill(0);
    for (let i = 0; i < this.ripples.length && i < 8; i++) {
      const r = this.ripples[i];
      out[i * 4] = r.x;
      out[i * 4 + 1] = r.z;
      out[i * 4 + 2] = r.born;
      out[i * 4 + 3] = r.strength;
    }
  }

  update(dt: number, time: number) {
    this.time = time;

    const dx = this.ndcX - this.lastX;
    const dy = this.ndcY - this.lastY;
    this.lastX = this.ndcX;
    this.lastY = this.ndcY;
    const instantSpeed = Math.min(1, Math.hypot(dx, dy) / (dt * 3 + 1e-4));
    this.speed = damp(this.speed, instantSpeed, 8, dt);

    this.smoothX = damp(this.smoothX, this.ndcX, 14, dt);
    this.smoothY = damp(this.smoothY, this.ndcY, 14, dt);

    this.pressAmount = damp(this.pressAmount, this.pressed ? 1 : 0, 16, dt);

    // Motion itself is excitement — moving the cursor stirs the field.
    this.energy = clamp(damp(this.energy, this.speed * 0.35, 1.3, dt), 0, 1);
    this.glitch = Math.max(0, damp(this.glitch, 0, 6, dt) - dt * 0.15);

    this.scanPhase += dt * 26;
    if (this.scanPhase > 55) this.scanPhase = -45;

    // Drop expired ripples so the uniform block stays meaningful.
    while (this.ripples.length && time - this.ripples[0].born > 3.2) this.ripples.shift();
  }
}
