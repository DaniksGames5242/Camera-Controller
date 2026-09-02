import { Spring, ScrambleText, ticker } from './motion.js';
import { clamp } from '../math.js';

/**
 * Screen-space overlays that belong to the desktop client specifically:
 * a keyboard-first command palette, a radial menu on right-click, and a
 * notification stack. All three assume a pointer that can hover without
 * committing and a keyboard that is always attached — assumptions the
 * Android client cannot make, which is why its equivalents are gestural.
 */

// ---------------------------------------------------------------- palette

export interface Command {
  id: string;
  title: string;
  hint?: string;
  group: string;
  run: () => void;
  /** Optional live status glyph shown at the right of the row. */
  badge?: () => string;
}

export class CommandPalette {
  private readonly root: HTMLElement;
  private readonly input: HTMLInputElement;
  private readonly list: HTMLElement;
  private readonly counter: HTMLElement;
  private commands: Command[] = [];
  private filtered: Command[] = [];
  private index = 0;
  open = false;
  private readonly reveal = new Spring(0, 190, 21);
  private onOpenChange: ((open: boolean) => void) | null = null;

  constructor() {
    this.root = document.createElement('div');
    this.root.className = 'palette-scrim';
    this.root.hidden = true;

    const panel = document.createElement('div');
    panel.className = 'palette';

    const header = document.createElement('div');
    header.className = 'palette-header';
    const prompt = document.createElement('span');
    prompt.className = 'palette-prompt';
    prompt.textContent = '›';
    this.input = document.createElement('input');
    this.input.className = 'palette-input';
    this.input.placeholder = 'команда, устройство, действие…';
    this.input.spellcheck = false;
    this.counter = document.createElement('span');
    this.counter.className = 'palette-counter';
    header.append(prompt, this.input, this.counter);

    this.list = document.createElement('div');
    this.list.className = 'palette-list';

    const footer = document.createElement('div');
    footer.className = 'palette-footer';
    footer.innerHTML =
      '<span><kbd>↑</kbd><kbd>↓</kbd> выбор</span>' +
      '<span><kbd>Enter</kbd> выполнить</span>' +
      '<span><kbd>Esc</kbd> закрыть</span>';

    panel.append(header, this.list, footer);
    for (const corner of ['tl', 'tr', 'bl', 'br']) {
      const c = document.createElement('i');
      c.className = `corner ${corner}`;
      panel.appendChild(c);
    }
    this.root.appendChild(panel);
    document.body.appendChild(this.root);

    this.input.addEventListener('input', () => this.refilter());
    this.input.addEventListener('keydown', this.onKey);
    this.root.addEventListener('pointerdown', (e) => {
      if (e.target === this.root) this.close();
    });

    ticker.add((dt) => {
      const v = this.reveal.update(dt);
      if (v < 0.001 && !this.open) {
        this.root.hidden = true;
        return;
      }
      this.root.style.setProperty('--reveal', v.toFixed(4));
    });
  }

  setCommands(commands: Command[]) {
    this.commands = commands;
    if (this.open) this.refilter();
  }

  onToggle(cb: (open: boolean) => void) { this.onOpenChange = cb; }

  toggle() { this.open ? this.close() : this.show(); }

  show() {
    this.open = true;
    this.root.hidden = false;
    this.reveal.to(1);
    this.input.value = '';
    this.refilter();
    // Focus after the frame that unhides it, or the browser drops it.
    requestAnimationFrame(() => this.input.focus());
    this.onOpenChange?.(true);
  }

  close() {
    if (!this.open) return;
    this.open = false;
    this.reveal.to(0);
    this.input.blur();
    this.onOpenChange?.(false);
  }

  private onKey = (e: KeyboardEvent) => {
    if (e.key === 'Escape') { this.close(); e.preventDefault(); return; }
    if (e.key === 'ArrowDown') { this.move(1); e.preventDefault(); return; }
    if (e.key === 'ArrowUp') { this.move(-1); e.preventDefault(); return; }
    if (e.key === 'Enter') {
      const cmd = this.filtered[this.index];
      if (cmd) { this.close(); cmd.run(); }
      e.preventDefault();
    }
  };

  private move(delta: number) {
    if (this.filtered.length === 0) return;
    this.index = (this.index + delta + this.filtered.length) % this.filtered.length;
    this.paintSelection();
  }

  private refilter() {
    const query = this.input.value.trim().toLowerCase();
    this.filtered = query
      ? this.commands
          .map((c) => ({ c, score: fuzzyScore(`${c.group} ${c.title} ${c.hint ?? ''}`.toLowerCase(), query) }))
          .filter((x) => x.score > 0)
          .sort((a, b) => b.score - a.score)
          .map((x) => x.c)
      : this.commands;
    this.index = 0;
    this.paintList();
  }

  private paintList() {
    this.list.textContent = '';
    this.counter.textContent = `${this.filtered.length}`;
    let lastGroup = '';
    this.filtered.forEach((cmd, i) => {
      if (cmd.group !== lastGroup) {
        lastGroup = cmd.group;
        const g = document.createElement('div');
        g.className = 'palette-group';
        g.textContent = cmd.group;
        this.list.appendChild(g);
      }
      const row = document.createElement('button');
      row.type = 'button';
      row.className = 'palette-row';
      row.dataset.index = String(i);
      const title = document.createElement('span');
      title.className = 'palette-title';
      title.textContent = cmd.title;
      const hint = document.createElement('span');
      hint.className = 'palette-hint';
      hint.textContent = cmd.badge?.() ?? cmd.hint ?? '';
      row.append(title, hint);
      row.addEventListener('pointerenter', () => { this.index = i; this.paintSelection(); });
      row.addEventListener('click', () => { this.close(); cmd.run(); });
      this.list.appendChild(row);
    });
    this.paintSelection();
  }

  private paintSelection() {
    for (const el of Array.from(this.list.querySelectorAll<HTMLElement>('.palette-row'))) {
      const on = Number(el.dataset.index) === this.index;
      el.classList.toggle('selected', on);
      if (on) el.scrollIntoView({ block: 'nearest' });
    }
  }
}

/**
 * Subsequence match with a bonus for hits at word starts — enough to make
 * "cam2" find "Камера 2 · открыть" without pulling in a whole matcher.
 */
function fuzzyScore(haystack: string, needle: string): number {
  let score = 0;
  let hi = 0;
  for (let ni = 0; ni < needle.length; ni++) {
    const ch = needle[ni];
    const found = haystack.indexOf(ch, hi);
    if (found < 0) return 0;
    score += found === 0 || haystack[found - 1] === ' ' ? 3 : 1;
    // Consecutive matches are worth more than scattered ones.
    if (found === hi) score += 2;
    hi = found + 1;
  }
  return score + Math.max(0, 12 - haystack.length / 8);
}

// ------------------------------------------------------------------ toasts

export type ToastTone = 'info' | 'ok' | 'warn' | 'error';

export class ToastStack {
  private readonly root: HTMLElement;

  constructor() {
    this.root = document.createElement('div');
    this.root.className = 'toast-stack';
    document.body.appendChild(this.root);
  }

  push(text: string, tone: ToastTone = 'info', ttl = 4200) {
    const el = document.createElement('div');
    el.className = `toast ${tone}`;
    const bar = document.createElement('i');
    bar.className = 'toast-bar';
    const label = document.createElement('span');
    label.className = 'toast-text';
    const scramble = new ScrambleText(label, 1.8);
    scramble.setInstant('');
    el.append(bar, label);
    this.root.appendChild(el);
    requestAnimationFrame(() => {
      el.classList.add('in');
      scramble.set(text);
    });

    const life = window.setTimeout(() => {
      el.classList.remove('in');
      el.classList.add('out');
      window.setTimeout(() => el.remove(), 420);
    }, ttl);

    el.addEventListener('click', () => {
      window.clearTimeout(life);
      el.classList.add('out');
      window.setTimeout(() => el.remove(), 300);
    });
  }
}

// ------------------------------------------------------------- radial menu

export interface RadialItem {
  label: string;
  glyph: string;
  run: () => void;
  danger?: boolean;
}

/**
 * Right-click menu laid out on an arc around the pointer. On a desktop the
 * pointer is already exactly where the user is looking, so putting actions
 * *around* it is both faster (equal, short travel to every item) and far
 * more legible than a list that drops below.
 */
export class RadialMenu {
  private static readonly RADIUS_PX = 132;

  private readonly root: HTMLElement;
  private items: RadialItem[] = [];
  private readonly reveal = new Spring(0, 210, 20);
  private buttons: HTMLElement[] = [];
  open = false;

  constructor() {
    this.root = document.createElement('div');
    this.root.className = 'radial';
    this.root.hidden = true;
    document.body.appendChild(this.root);

    window.addEventListener('pointerdown', (e) => {
      if (this.open && !this.root.contains(e.target as Node)) this.close();
    });
    window.addEventListener('keydown', (e) => { if (e.key === 'Escape') this.close(); });

    ticker.add((dt) => {
      const v = this.reveal.update(dt);
      if (v < 0.002 && !this.open) { this.root.hidden = true; return; }
      const count = this.buttons.length;
      if (count > 0) {
        // Solve the angular step from the geometry, not a guess: each item
        // is a ~78px box, and the previous fixed radius/spread combination
        // let adjacent boxes overlap at 3-4 items (their arc chord was
        // narrower than the box itself). minDelta is the angle at which two
        // boxes exactly touch at RADIUS_PX; spacing every item by at least
        // that guarantees no overlap regardless of count.
        const footprint = 96; // item width + a visible gap, in px
        const minDelta = 2 * Math.asin(Math.min(1, footprint / (2 * RadialMenu.RADIUS_PX)));
        const spread = Math.min(Math.PI * 1.7, minDelta * count);
        this.buttons.forEach((btn, i) => {
          const angle = -Math.PI / 2 - spread / 2 + (spread * (i + 0.5)) / count;
          const radius = RadialMenu.RADIUS_PX * v;
          const x = Math.cos(angle) * radius;
          const y = Math.sin(angle) * radius;
          btn.style.transform = `translate(-50%, -50%) translate(${x}px, ${y}px) scale(${clamp(v, 0, 1)})`;
          btn.style.opacity = String(clamp(v * 1.4 - i * 0.05, 0, 1));
        });
      }
      this.root.style.setProperty('--reveal', v.toFixed(3));
    });
  }

  show(x: number, y: number, items: RadialItem[]) {
    this.items = items;
    this.root.hidden = false;
    this.root.style.left = `${x}px`;
    this.root.style.top = `${y}px`;
    this.root.textContent = '';
    this.buttons = items.map((item) => {
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = `radial-item${item.danger ? ' danger' : ''}`;
      const glyph = document.createElement('span');
      glyph.className = 'radial-glyph';
      glyph.textContent = item.glyph;
      const label = document.createElement('span');
      label.className = 'radial-label';
      label.textContent = item.label;
      btn.append(glyph, label);
      btn.addEventListener('click', () => { this.close(); item.run(); });
      this.root.appendChild(btn);
      return btn;
    });
    const hub = document.createElement('i');
    hub.className = 'radial-hub';
    this.root.appendChild(hub);
    this.open = true;
    this.reveal.set(0);
    this.reveal.to(1);
  }

  close() {
    if (!this.open) return;
    this.open = false;
    this.reveal.to(0);
  }
}

// ---------------------------------------------------------------- confirm

/**
 * A destructive action, asked about in the app's own voice instead of a
 * bare OS `confirm()` — which is a plain system dialog that breaks the
 * hologram illusion outright the moment it appears.
 */
export class HoloConfirm {
  private readonly root: HTMLElement;
  private readonly panel: HTMLElement;
  private readonly titleEl: HTMLElement;
  private readonly bodyEl: HTMLElement;
  private readonly confirmBtn: HTMLButtonElement;
  private readonly cancelBtn: HTMLButtonElement;
  private readonly reveal = new Spring(0, 190, 20);
  private resolve: ((ok: boolean) => void) | null = null;
  private open = false;

  constructor() {
    this.root = document.createElement('div');
    this.root.className = 'confirm-scrim';
    this.root.hidden = true;

    this.panel = document.createElement('div');
    this.panel.className = 'confirm-panel danger';
    for (const corner of ['tl', 'tr', 'bl', 'br']) {
      const c = document.createElement('i');
      c.className = `corner ${corner}`;
      this.panel.appendChild(c);
    }

    this.titleEl = document.createElement('h3');
    this.titleEl.className = 'confirm-title';
    this.bodyEl = document.createElement('p');
    this.bodyEl.className = 'confirm-body';

    const actions = document.createElement('div');
    actions.className = 'confirm-actions';
    this.cancelBtn = document.createElement('button');
    this.cancelBtn.type = 'button';
    this.cancelBtn.className = 'confirm-btn ghost';
    this.cancelBtn.textContent = 'ОТМЕНА';
    this.confirmBtn = document.createElement('button');
    this.confirmBtn.type = 'button';
    this.confirmBtn.className = 'confirm-btn go';
    actions.append(this.cancelBtn, this.confirmBtn);

    this.panel.append(this.titleEl, this.bodyEl, actions);
    this.root.appendChild(this.panel);
    document.body.appendChild(this.root);

    this.cancelBtn.onclick = () => this.finish(false);
    this.confirmBtn.onclick = () => this.finish(true);
    this.root.addEventListener('pointerdown', (e) => { if (e.target === this.root) this.finish(false); });
    window.addEventListener('keydown', (e) => {
      if (!this.open) return;
      if (e.key === 'Escape') { this.finish(false); e.preventDefault(); }
      if (e.key === 'Enter') { this.finish(true); e.preventDefault(); }
    });

    ticker.add((dt) => {
      const v = this.reveal.update(dt);
      if (v < 0.001 && !this.open) { this.root.hidden = true; return; }
      this.root.style.setProperty('--reveal', v.toFixed(4));
    });
  }

  /** Resolves true if confirmed, false if cancelled or dismissed. */
  ask(title: string, body: string, confirmLabel: string): Promise<boolean> {
    this.titleEl.textContent = title;
    this.bodyEl.textContent = body;
    this.confirmBtn.textContent = confirmLabel;
    this.root.hidden = false;
    this.open = true;
    this.reveal.set(0);
    this.reveal.to(1);
    return new Promise((resolve) => { this.resolve = resolve; });
  }

  private finish(ok: boolean) {
    if (!this.open) return;
    this.open = false;
    this.reveal.to(0);
    const r = this.resolve;
    this.resolve = null;
    r?.(ok);
  }
}
