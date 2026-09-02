import { Damper, Spring, ticker } from './motion.js';
import { clamp } from '../math.js';

/**
 * The desktop pointer.
 *
 * A mouse is a precise, high-frequency, always-visible instrument, and the
 * Windows client is built around that: the cursor becomes an instrument of
 * the hologram itself — a reticle that lags slightly behind the true
 * pointer, snaps magnetically onto whatever it can act on, reports what
 * that thing does, and disturbs the light field around it.
 *
 * None of this exists on the Android client, where a finger occludes the
 * very pixel it is pointing at and the equivalent affordances are pressure,
 * ripple and haptics instead.
 */

export interface MagneticTarget {
  el: HTMLElement;
  /** How strongly the reticle is drawn in, 0..1. */
  strength: number;
  label?: string;
}

export class HoloCursor {
  private readonly root: HTMLElement;
  private readonly ring: HTMLElement;
  private readonly dot: HTMLElement;
  private readonly labelEl: HTMLElement;
  private readonly trail: HTMLElement[] = [];

  private rawX = -100;
  private rawY = -100;
  private readonly x = new Damper(-100, 26);
  private readonly y = new Damper(-100, 26);
  private readonly scale = new Spring(1, 260, 20);
  private readonly ringScale = new Spring(1, 150, 14);
  private readonly spin = { value: 0, velocity: 40 };
  private readonly snap = new Damper(0, 14);

  private target: MagneticTarget | null = null;
  private down = false;

  constructor(private readonly container: HTMLElement) {
    this.root = document.createElement('div');
    this.root.className = 'holo-cursor';
    this.root.setAttribute('aria-hidden', 'true');

    this.ring = document.createElement('div');
    this.ring.className = 'holo-cursor-ring';
    // Four brackets rather than a circle: it reads as an instrument and it
    // can open, close and rotate to signal state.
    for (let i = 0; i < 4; i++) {
      const bracket = document.createElement('span');
      bracket.className = `holo-cursor-bracket b${i}`;
      this.ring.appendChild(bracket);
    }

    this.dot = document.createElement('div');
    this.dot.className = 'holo-cursor-dot';

    this.labelEl = document.createElement('div');
    this.labelEl.className = 'holo-cursor-label';

    this.root.append(this.ring, this.dot, this.labelEl);

    // A short comet tail of ghosts, each lagging a little more than the
    // last, so fast movement leaves a light streak.
    for (let i = 0; i < 5; i++) {
      const ghost = document.createElement('div');
      ghost.className = 'holo-cursor-ghost';
      ghost.style.opacity = String(0.30 - i * 0.05);
      this.container.appendChild(ghost);
      this.trail.push(ghost);
    }
    this.container.appendChild(this.root);

    window.addEventListener('pointermove', this.onMove, { passive: true });
    window.addEventListener('pointerdown', this.onDown, { passive: true });
    window.addEventListener('pointerup', this.onUp, { passive: true });
    window.addEventListener('pointerleave', this.onLeave, { passive: true });
    ticker.add(this.update);
  }

  private onMove = (e: PointerEvent) => {
    this.rawX = e.clientX;
    this.rawY = e.clientY;
    this.root.classList.add('visible');
  };

  private onDown = () => {
    this.down = true;
    this.scale.to(0.68);
    this.ringScale.kick(-6);
    this.spin.velocity += 260;
  };

  private onUp = () => {
    this.down = false;
    this.scale.to(1);
    this.ringScale.kick(4);
  };

  private onLeave = () => this.root.classList.remove('visible');

  /** Called by the app when the pointer enters/leaves an actionable thing. */
  setTarget(target: MagneticTarget | null) {
    this.target = target;
    this.snap.to(target ? target.strength : 0);
    this.ringScale.to(target ? 1.55 : 1);
    this.root.classList.toggle('locked', !!target);
    if (target?.label) {
      this.labelEl.textContent = target.label;
      this.labelEl.classList.add('visible');
    } else {
      this.labelEl.classList.remove('visible');
    }
  }

  private update = (dt: number) => {
    let targetX = this.rawX;
    let targetY = this.rawY;

    // Magnetism: pull the reticle toward the centre of whatever it is over,
    // proportionally to how strongly that thing wants to be hit. Small
    // controls become much easier to land on without changing their size.
    if (this.target) {
      const rect = this.target.el.getBoundingClientRect();
      const cx = rect.left + rect.width / 2;
      const cy = rect.top + rect.height / 2;
      const k = this.snap.value;
      targetX += (cx - targetX) * k;
      targetY += (cy - targetY) * k;
    }
    this.snap.update(dt);

    this.x.to(targetX);
    this.y.to(targetY);
    const px = this.x.update(dt);
    const py = this.y.update(dt);
    const s = this.scale.update(dt);
    const rs = this.ringScale.update(dt);

    // Free-spinning ring with drag: impulses from clicks decay naturally
    // instead of following a fixed animation.
    this.spin.velocity += (34 - this.spin.velocity) * clamp(dt * 1.4, 0, 1);
    this.spin.value += this.spin.velocity * dt;

    this.root.style.transform =
      `translate3d(${px}px, ${py}px, 0) scale(${s})`;
    this.ring.style.transform = `rotate(${this.spin.value}deg) scale(${rs})`;
    this.dot.style.transform = `scale(${this.down ? 1.9 : 1})`;

    for (let i = 0; i < this.trail.length; i++) {
      const lag = 0.55 - i * 0.08;
      const gx = px + (this.rawX - px) * lag;
      const gy = py + (this.rawY - py) * lag;
      this.trail[i].style.transform = `translate3d(${gx}px, ${gy}px, 0) scale(${1 - i * 0.12})`;
    }
  };
}
