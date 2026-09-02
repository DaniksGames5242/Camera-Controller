import { clamp, damp } from '../math.js';

/**
 * Procedural motion for DOM.
 *
 * There is not a single CSS transition or keyframe timing function driving
 * interface state in this app. Everything that moves is integrated every frame
 * from a physical model, because that is the only way a value can be
 * retargeted mid-flight — hover away before the hover finished, close a
 * panel while it is still opening — without a visible discontinuity.
 */

export class Spring {
  value: number;
  velocity = 0;
  target: number;

  constructor(value = 0, public stiffness = 170, public damping = 22) {
    this.value = value;
    this.target = value;
  }

  set(value: number) {
    this.value = value;
    this.target = value;
    this.velocity = 0;
  }

  to(target: number) { this.target = target; }

  /** Adds an instantaneous impulse — the physical way to express a "kick". */
  kick(velocity: number) { this.velocity += velocity; }

  update(dt: number): number {
    // Sub-stepped: a stiff spring integrated with one big step at 20 fps
    // will overshoot to infinity, and dropped frames are not hypothetical.
    const steps = Math.min(6, Math.max(1, Math.ceil(dt / (1 / 120))));
    const h = dt / steps;
    for (let i = 0; i < steps; i++) {
      const accel = (this.target - this.value) * this.stiffness - this.velocity * this.damping;
      this.velocity += accel * h;
      this.value += this.velocity * h;
    }
    return this.value;
  }

  get settled() {
    return Math.abs(this.value - this.target) < 0.0005 && Math.abs(this.velocity) < 0.0005;
  }
}

/** Exponential approach — for values where overshoot would be wrong. */
export class Damper {
  value: number;
  target: number;
  constructor(value = 0, public rate = 10) {
    this.value = value;
    this.target = value;
  }
  to(target: number) { this.target = target; }
  set(value: number) { this.value = value; this.target = value; }
  update(dt: number): number {
    this.value = damp(this.value, this.target, this.rate, dt);
    return this.value;
  }
}

/**
 * A shared ticker. One requestAnimationFrame for the whole DOM layer keeps
 * the ordering deterministic and means adding an animation costs nothing.
 */
export class Ticker {
  private readonly callbacks = new Set<(dt: number, time: number) => void>();
  private last = 0;
  private handle = 0;
  private running = false;
  time = 0;

  add(cb: (dt: number, time: number) => void): () => void {
    this.callbacks.add(cb);
    return () => this.callbacks.delete(cb);
  }

  start() {
    if (this.running) return;
    this.running = true;
    this.last = performance.now();
    const loop = (now: number) => {
      if (!this.running) return;
      this.handle = requestAnimationFrame(loop);
      const dt = clamp((now - this.last) / 1000, 1 / 240, 1 / 20);
      this.last = now;
      this.time += dt;
      for (const cb of this.callbacks) cb(dt, this.time);
    };
    this.handle = requestAnimationFrame(loop);
  }

  stop() {
    this.running = false;
    cancelAnimationFrame(this.handle);
  }
}

export const ticker = new Ticker();

const GLYPHS = 'АБВГДЕЖЗИКЛМНОПРСТУФХЦЧШЩЭЮЯ0123456789#%&@*<>/\\|=+-';

/**
 * Decodes text one glyph at a time out of a scrambling cipher.
 *
 * Used for every label that changes at runtime. It is not decoration: a
 * value that scrambles and resolves is impossible to miss, so status
 * changes never need a flash or a colour alarm to be noticed.
 */
export class ScrambleText {
  /** Progress in nominal 60 Hz frames, accumulated from real time so the
      effect runs at the same speed whatever the render is managing. */
  private frame = 0;
  private queue: Array<{ from: string; to: string; start: number; end: number; glyph: string }> = [];
  private stop: (() => void) | null = null;
  private current = '';

  constructor(private readonly el: HTMLElement, private readonly speed = 1) {}

  setInstant(text: string) {
    this.stop?.();
    this.stop = null;
    this.queue = [];
    this.current = text;
    this.el.textContent = text;
  }

  set(text: string) {
    if (text === this.current) return;
    const from = this.current;
    this.current = text;
    const length = Math.max(from.length, text.length);
    this.queue = [];
    for (let i = 0; i < length; i++) {
      const start = Math.floor(Math.random() * 12 / this.speed);
      const end = start + Math.floor(Math.random() * 14 / this.speed) + 4;
      this.queue.push({ from: from[i] ?? '', to: text[i] ?? '', start, end, glyph: '' });
    }
    this.frame = 0;
    this.stop?.();
    this.stop = ticker.add((dt) => this.tick(dt));
  }

  private tick(dt: number) {
    let output = '';
    let complete = 0;
    for (const item of this.queue) {
      if (this.frame >= item.end) {
        complete++;
        output += item.to;
      } else if (this.frame >= item.start) {
        if (!item.glyph || Math.random() < 0.3) {
          item.glyph = GLYPHS[Math.floor(Math.random() * GLYPHS.length)];
        }
        output += item.glyph;
      } else {
        output += item.from;
      }
    }
    this.el.textContent = output;
    this.frame += dt * 60;
    if (complete === this.queue.length) {
      this.stop?.();
      this.stop = null;
      this.el.textContent = this.current;
    }
  }

  dispose() { this.stop?.(); }
}

/**
 * Splits an element's text into per-glyph spans so each can be animated
 * independently. Returns the spans in document order.
 */
export function splitGlyphs(el: HTMLElement, text: string): HTMLSpanElement[] {
  el.textContent = '';
  const spans: HTMLSpanElement[] = [];
  for (const ch of text) {
    const span = document.createElement('span');
    span.className = 'glyph';
    // A space with no glyph collapses; a non-breaking space keeps the
    // rhythm of the line intact while still animating.
    span.textContent = ch === ' ' ? ' ' : ch;
    span.style.setProperty('--glyph-index', String(spans.length));
    el.appendChild(span);
    spans.push(span);
  }
  return spans;
}
