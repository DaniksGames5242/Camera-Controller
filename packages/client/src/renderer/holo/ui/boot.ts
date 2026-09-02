import type { HoloStage } from '../stage.js';
import { textPointCloud } from '../pointcloud.js';
import { clamp, smoothstep } from '../math.js';
import { ticker } from './motion.js';

/**
 * Entry into the application.
 *
 * There is no separate splash screen and no loading spinner: the stage the
 * app will actually run in powers on in front of you, the swarm of dust
 * that will keep drifting for the whole session condenses into the product
 * mark, and then disperses back into the room. The signalling connection
 * negotiates underneath it in parallel, and the sequence is skippable at
 * any point — it never gates the interface.
 */

interface BootLine {
  at: number;
  text: string;
  tone?: 'ok' | 'warn' | 'accent';
}

const LINES: BootLine[] = [
  { at: 1.30, text: 'ПРОЕКТОР · ЗАПУСК ЭМИТТЕРА', tone: 'accent' },
  { at: 1.62, text: 'ОБЪЁМНАЯ СЕТКА · КАЛИБРОВКА' },
  { at: 1.94, text: 'КАНАЛ СИГНАЛИНГА · FIREBASE RTDB', tone: 'ok' },
  { at: 2.24, text: 'ТРАНСПОРТ · WEBRTC / STUN + TURN', tone: 'ok' },
  { at: 2.54, text: 'СКАНИРОВАНИЕ УЗЛОВ СЕТИ…', tone: 'accent' },
];

export class BootSequence {
  private readonly overlay: HTMLElement;
  private readonly readout: HTMLElement;
  private readonly hint: HTMLElement;
  private elapsed = 0;
  private emitted = 0;
  private skipped = false;
  private done = false;
  private stopTicker: (() => void) | null = null;
  private resolve!: () => void;

  constructor(
    private readonly stage: HoloStage,
    private readonly reduceMotion: boolean
  ) {
    this.overlay = document.createElement('div');
    this.overlay.id = 'boot-overlay';
    this.overlay.innerHTML = '';

    this.readout = document.createElement('div');
    this.readout.className = 'boot-readout';

    this.hint = document.createElement('div');
    this.hint.className = 'boot-hint';
    this.hint.textContent = 'нажмите любую клавишу, чтобы пропустить';

    this.overlay.append(this.readout, this.hint);
    document.body.appendChild(this.overlay);
  }

  run(): Promise<void> {
    return new Promise<void>((resolve) => {
      this.resolve = resolve;

      if (this.reduceMotion) {
        this.stage.boot = 1;
        this.finish(0);
        return;
      }

      // The mark is assembled from the same particles that will keep
      // drifting through the session — nothing is spawned just for this.
      this.stage.particles.setTargets(
        textPointCloud('MYCAMS//CTRL', {
          width: 13,
          count: 9000,
          y: 1.4,
          z: 0.6,
          depth: 0.6,
          font: '700 132px ui-monospace, "Cascadia Code", "JetBrains Mono", Consolas, monospace',
        })
      );

      window.addEventListener('keydown', this.onSkip, { once: true });
      window.addEventListener('pointerdown', this.onSkip, { once: true });
      this.stopTicker = ticker.add(this.tick);
    });
  }

  private onSkip = () => {
    if (this.done) return;
    this.skipped = true;
  };

  private tick = (dt: number) => {
    this.elapsed += dt;
    const t = this.elapsed;

    // --- the room powers on ------------------------------------------------
    this.stage.boot = clamp(smoothstep(0.05, 1.5, t) * 0.55 + smoothstep(2.4, 3.3, t) * 0.45, 0, 1);

    // --- the swarm condenses into the mark, holds, then lets go ------------
    const gather = smoothstep(0.35, 1.7, t);
    const release = smoothstep(2.55, 3.35, t);
    this.stage.particles.targetMix = clamp(gather - release, 0, 1);

    // The camera pulls back as the room resolves, so the reveal has scale.
    this.stage.camera.setFocus(0, 0, 22 - 6 * smoothstep(0.2, 3.0, t));

    // --- readout -----------------------------------------------------------
    while (this.emitted < LINES.length && t >= LINES[this.emitted].at) {
      this.emitLine(LINES[this.emitted]);
      this.emitted++;
    }

    if (Math.abs(t - 2.6) < dt) {
      this.stage.interaction.kickGlitch(0.8);
      this.stage.interaction.ripple(0, 0, 1.4);
      this.stage.interaction.triggerScan();
    }

    if (t > 0.35) this.hint.classList.add('visible');

    if (this.skipped || t > 3.6) this.finish(this.skipped ? 0.28 : 0.5);
  };

  private emitLine(line: BootLine) {
    const row = document.createElement('div');
    row.className = `boot-line ${line.tone ?? ''}`;
    const marker = document.createElement('span');
    marker.className = 'boot-marker';
    marker.textContent = '▍';
    const text = document.createElement('span');
    text.className = 'boot-text';
    text.textContent = line.text;
    row.append(marker, text);
    this.readout.appendChild(row);
    // Each line arriving disturbs the field — the readout is diegetic.
    this.stage.interaction.energy = Math.min(1, this.stage.interaction.energy + 0.18);
    requestAnimationFrame(() => row.classList.add('in'));
  }

  private finish(fade: number) {
    if (this.done) return;
    this.done = true;
    this.stopTicker?.();
    window.removeEventListener('keydown', this.onSkip);
    window.removeEventListener('pointerdown', this.onSkip);

    this.stage.boot = 1;
    this.stage.particles.targetMix = 0;
    this.stage.camera.setFocus(0, 0, 16);
    this.overlay.classList.add('done');
    this.stage.interaction.kickGlitch(0.55);

    // Keep the cloud around: closing a session later re-uses it to scatter
    // the panel's pixels, so it must not be thrown away here.
    window.setTimeout(() => {
      this.overlay.remove();
      this.resolve();
    }, fade * 1000);
  }
}
