import { BackgroundPass } from './background.js';
import { Camera } from './camera.js';
import { FullscreenTriangle, detectCaps, type GLCaps } from './gl.js';
import { Interaction } from './interaction.js';
import { ParticleSystem } from './particles.js';
import { Panel, PanelPass, type PanelInit } from './panels.js';
import { PostChain } from './post.js';
import { screenToWorld } from './project.js';
import { clamp, damp } from './math.js';

/**
 * The hologram stage: one canvas, one render loop, one coherent projection
 * that every other part of the UI hangs off.
 *
 * It owns the render graph (volumetric room → slabs → particles → optics),
 * the input plumbing that feeds the shaders, and an adaptive quality
 * governor. Everything above it — the device list, the boot sequence, the
 * settings console — talks to it through panels, bursts and the boot
 * parameter, never through WebGL directly.
 */

export interface StageOptions {
  canvas: HTMLCanvasElement;
  reduceMotion?: boolean;
}

export class HoloStage {
  readonly canvas: HTMLCanvasElement;
  readonly gl: WebGL2RenderingContext;
  readonly caps: GLCaps;
  readonly camera = new Camera();
  readonly interaction = new Interaction();
  readonly panels: PanelPass;
  readonly particles: ParticleSystem;

  private readonly quad: FullscreenTriangle;
  private readonly background: BackgroundPass;
  private readonly post: PostChain;

  private readonly frameCallbacks: Array<(dt: number, time: number) => void> = [];

  reduceMotion: boolean;
  /** 0..1 render-feature dial, moved automatically by the frame timer. */
  quality = 1;
  /** Multiplier on the backing-store resolution; the first thing sacrificed. */
  renderScale = 1;
  /** 0 = dark, 1 = fully powered up. Driven by the boot sequence. */
  boot = 0;

  private time = 0;
  private lastFrame = 0;
  private frameCost = 16;
  private running = false;
  private rafHandle = 0;
  private width = 1;
  private height = 1;

  constructor(opts: StageOptions) {
    this.canvas = opts.canvas;
    this.reduceMotion = opts.reduceMotion ?? false;

    const gl = this.canvas.getContext('webgl2', {
      alpha: false,
      antialias: false,        // resolved by the post chain instead
      depth: false,            // the scene buffer carries its own depth
      stencil: false,
      premultipliedAlpha: false,
      powerPreference: 'high-performance',
      preserveDrawingBuffer: false,
    });
    if (!gl) throw new Error('WebGL2 unavailable');
    this.gl = gl;
    this.caps = detectCaps(gl);

    this.quad = new FullscreenTriangle(gl);
    this.background = new BackgroundPass(gl, this.quad);
    // A smaller solver grid on machines without float targets is moot (the
    // analytic path ignores it), but it keeps the point count honest.
    this.particles = new ParticleSystem(gl, this.quad, this.caps, this.reduceMotion ? 48 : 96);
    this.panels = new PanelPass(gl);
    this.post = new PostChain(gl, this.quad, this.caps, 16, 16);

    this.resize();
  }

  // ---------------------------------------------------------------- panels

  addPanel(init: PanelInit): Panel {
    const panel = new Panel(init);
    panel.materialize = 0;
    panel.tMaterialize = 1;
    return this.panels.add(panel);
  }

  removePanel(panel: Panel) {
    this.panels.remove(panel);
  }

  // ------------------------------------------------------------- lifecycle

  onFrame(cb: (dt: number, time: number) => void) {
    this.frameCallbacks.push(cb);
  }

  start() {
    if (this.running) return;
    this.running = true;
    this.lastFrame = performance.now();
    const loop = (now: number) => {
      if (!this.running) return;
      this.rafHandle = requestAnimationFrame(loop);
      this.frame(now);
    };
    this.rafHandle = requestAnimationFrame(loop);
  }

  stop() {
    this.running = false;
    cancelAnimationFrame(this.rafHandle);
  }

  resize() {
    const dpr = clamp(window.devicePixelRatio || 1, 1, 2);
    const cssWidth = this.canvas.clientWidth || window.innerWidth;
    const cssHeight = this.canvas.clientHeight || window.innerHeight;
    const w = Math.max(2, Math.floor(cssWidth * dpr * this.renderScale));
    const h = Math.max(2, Math.floor(cssHeight * dpr * this.renderScale));
    if (w === this.width && h === this.height) return;
    this.width = w;
    this.height = h;
    this.canvas.width = w;
    this.canvas.height = h;
    this.camera.aspect = w / h;
    this.post.resize(w, h);
  }

  /** World-space point under the pointer, on the plane the slabs live on. */
  pointerWorld(depth = 0): [number, number, number] {
    return screenToWorld(this.camera, this.interaction.smoothX, this.interaction.smoothY, depth);
  }

  private frame(now: number) {
    const rawDt = (now - this.lastFrame) / 1000;
    this.lastFrame = now;
    // Clamp: a backgrounded window returns with a multi-second delta that
    // would otherwise fire every spring across the screen at once.
    const dt = clamp(rawDt, 1 / 240, 1 / 20);
    this.time += dt;

    this.governQuality(rawDt * 1000);
    this.resize();

    this.interaction.update(dt, this.time);
    this.camera.setParallax(this.interaction.smoothX, this.interaction.smoothY);
    this.camera.update(dt, this.time);
    this.panels.update(dt);

    for (const cb of this.frameCallbacks) cb(dt, this.time);

    const gl = this.gl;
    const pointer = this.pointerWorld(0);

    if (!this.reduceMotion) {
      this.particles.simulate(dt, this.time, this.interaction, pointer);
    }

    this.post.beginScene();
    gl.viewport(0, 0, this.width, this.height);

    this.background.render(this.camera, this.interaction, this.time, this.quality, this.boot, this.reduceMotion);
    this.panels.render(this.camera, this.time, this.quality);
    this.particles.render(
      this.camera,
      this.time,
      this.interaction,
      this.height * 0.20 * (0.7 + 0.3 * this.quality),
      Math.max(0.08, this.boot)
    );

    this.post.finish(this.width, this.height, this.time, this.interaction, this.boot, this.reduceMotion);
  }

  /**
   * Keeps the frame budget by trading resolution first and shader detail
   * second — the ordering that costs the least perceived fidelity.
   */
  private governQuality(frameMs: number) {
    this.frameCost = damp(this.frameCost, clamp(frameMs, 1, 200), 2.5, 1 / 60);
    if (this.frameCost > 26) {
      this.quality = Math.max(0, this.quality - 0.02);
      if (this.quality <= 0.02) this.renderScale = Math.max(0.55, this.renderScale - 0.01);
    } else if (this.frameCost < 13) {
      if (this.renderScale < 1) this.renderScale = Math.min(1, this.renderScale + 0.004);
      else this.quality = Math.min(1, this.quality + 0.01);
    }
  }

  dispose() {
    this.stop();
    this.background.dispose();
    this.particles.dispose();
    this.panels.dispose();
    this.post.dispose();
  }
}

export { Panel };
