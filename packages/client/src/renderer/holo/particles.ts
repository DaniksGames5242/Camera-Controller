import { FULLSCREEN_VERT, Program, createDataTexture, type FullscreenTriangle, type GLCaps } from './gl.js';
import { NOISE } from './glsl.js';
import type { Camera } from './camera.js';
import type { Interaction } from './interaction.js';

/**
 * The luminous dust the whole interface floats in.
 *
 * State (position, velocity, life) lives in floating-point textures and is
 * integrated on the GPU: a curl-noise flow field, drag, a weak pull toward
 * the stage axis, repulsion from the pointer, impulse bursts from user
 * events, and — the reason this is a solver and not a loop of sine waves —
 * an optional attraction to arbitrary target points, which is how the logo
 * assembles itself out of the dust during boot.
 *
 * Where floating-point render targets are unavailable the same renderer
 * runs from an analytic closed-form motion instead, so the layer degrades
 * rather than disappearing.
 */

const SIM_FRAG = /* glsl */ `#version 300 es
precision highp float;

in vec2 vUv;
layout(location = 0) out vec4 outPosition;
layout(location = 1) out vec4 outVelocity;

uniform sampler2D uPosTex;
uniform sampler2D uVelTex;
uniform sampler2D uTargetTex;

uniform float uTime;
uniform float uDt;
uniform float uEnergy;
uniform float uTargetMix;      // 0 = free flow, 1 = fully assembled onto targets
uniform float uHasTargets;
uniform vec3  uPointer;        // world-space pointer probe
uniform float uPointerForce;
uniform vec4  uBursts[6];      // xyz origin, w strength
uniform float uBounds;
uniform float uFloorY;

${NOISE}

void main() {
  vec4 P = texture(uPosTex, vUv);
  vec4 V = texture(uVelTex, vUv);

  vec3 pos = P.xyz;
  vec3 vel = V.xyz;
  float life = P.w;
  float seed = V.w;

  float dt = min(uDt, 0.05);

  // --- flow field ---------------------------------------------------------
  // The flow field is what keeps the swarm alive, but it has to yield while
  // the swarm is being asked to hold a shape — otherwise the glyphs boil.
  vec3 flow = curlNoise(pos * 0.085 + vec3(0.0, 0.0, uTime * 0.04));
  vel += flow * (1.6 + uEnergy * 2.4) * (1.0 - uTargetMix * 0.94) * dt;

  // --- containment: a soft spherical well keeps the swarm on stage --------
  float r = length(pos);
  if (r > uBounds) vel -= normalize(pos) * (r - uBounds) * 2.2 * dt;

  // --- pointer probe ------------------------------------------------------
  vec3 toPointer = pos - uPointer;
  float pd = length(toPointer) + 1e-4;
  // Repels up close, gently attracts at mid range: the cursor carves a
  // bubble in the dust and drags a wake behind it.
  float radial = uPointerForce * (exp(-pd * 0.55) * 9.0 - exp(-pd * 0.16) * 1.1);
  vel += (toPointer / pd) * radial * (1.0 - uTargetMix * 0.9) * dt;

  // --- impulse bursts -----------------------------------------------------
  for (int i = 0; i < 6; i++) {
    vec4 b = uBursts[i];
    if (b.w <= 0.0) continue;
    vec3 d = pos - b.xyz;
    float dist = length(d) + 1e-4;
    float falloff = exp(-dist * 0.30);
    vel += (d / dist) * falloff * b.w * 26.0 * dt;
    // A little tangential spin makes the shell curl instead of expanding
    // like a plain balloon.
    vel += cross(d / dist, vec3(0.0, 1.0, 0.0)) * falloff * b.w * 9.0 * dt;
  }

  // --- target assembly ----------------------------------------------------
  if (uHasTargets > 0.5 && uTargetMix > 0.001) {
    vec3 target = texture(uTargetTex, vUv).xyz;
    vec3 toTarget = target - pos;
    // A genuinely critically damped spring (c = 2√k): the swarm converges
    // on the glyphs without the overshoot that turns the mark into a blur.
    float k = 58.0 * uTargetMix * uTargetMix;
    float c = 2.0 * sqrt(k);
    vel += (toTarget * k - vel * c) * dt;
    // A little shimmer so the formed mark still breathes.
    vel += hash33(pos * 3.1 + seed * 17.0 + uTime) * 0.9 * uTargetMix * dt;
  }

  // --- integrate ----------------------------------------------------------
  vel *= exp(-dt * 1.15);
  pos += vel * dt;

  // Floor: inelastic bounce with a touch of scatter.
  if (pos.y < uFloorY) {
    pos.y = uFloorY + (uFloorY - pos.y) * 0.25;
    vel.y = abs(vel.y) * 0.35;
    vel.xz += hash33(pos * 7.0 + uTime).xz * 0.8;
  }

  // --- lifecycle ----------------------------------------------------------
  // Ageing pauses while the swarm is holding a shape: a respawn mid-word
  // punches a hole straight through the glyph it was part of.
  life -= dt * (0.055 + 0.05 * hash11(seed)) * (1.0 - uTargetMix);
  if (life <= 0.0) {
    // Respawn on a shell around the stage, biased low so the swarm looks
    // like it is rising off the floor.
    vec3 h = hash33(vec3(vUv * 137.0, uTime));
    float theta = h.x * 6.28318;
    float rad = uBounds * (0.18 + 0.82 * abs(h.y));
    // Biased low and toward the front of the room: the swarm should hug
    // the stage the camera is actually looking at.
    pos = vec3(cos(theta) * rad, uFloorY + pow(abs(h.z), 1.6) * 15.0, sin(theta) * rad * 0.6);
    vel = vec3(0.0);
    life = 1.0;
    seed = fract(seed + 0.6180339887);
  }

  outPosition = vec4(pos, life);
  outVelocity = vec4(vel, seed);
}
`;

const RENDER_VERT = /* glsl */ `#version 300 es
precision highp float;

uniform sampler2D uPosTex;
uniform sampler2D uVelTex;
uniform mat4 uViewProjection;
uniform vec2 uSimSize;
uniform float uPointScale;
uniform float uTime;
uniform float uEnergy;
uniform float uBoot;

out vec3 vColor;
out float vAlpha;

${NOISE}

void main() {
  int w = int(uSimSize.x);
  ivec2 tc = ivec2(gl_VertexID % w, gl_VertexID / w);

#ifdef ANALYTIC
  // Closed-form fallback: a rotating toroidal drift with per-particle
  // phase. No state, no float targets, still alive.
  float id = float(gl_VertexID);
  float a = hash11(id) * 6.28318;
  float b = hash11(id + 91.7);
  float rad = 6.0 + b * 12.0;
  float t = uTime * (0.06 + 0.05 * hash11(id + 3.1));
  vec3 pos = vec3(cos(a + t) * rad, -3.0 + hash11(id + 17.0) * 16.0 + sin(t * 2.3 + a) * 1.5, sin(a + t) * rad * 0.7);
  pos += curlNoise(pos * 0.09 + uTime * 0.04) * 1.6;
  float life = 0.35 + 0.65 * hash11(id + 51.0);
  float speed = 0.35;
#else
  vec4 P = texelFetch(uPosTex, tc, 0);
  vec4 V = texelFetch(uVelTex, tc, 0);
  vec3 pos = P.xyz;
  float life = P.w;
  float speed = clamp(length(V.xyz) * 0.12, 0.0, 1.0);
#endif

  vec4 clip = uViewProjection * vec4(pos, 1.0);
  gl_Position = clip;

  // Size varies steeply per particle: mostly fine sparks with a few soft
  // motes, which reads as depth of field rather than as uniform noise.
  float sizeVar = 0.30 + 2.2 * pow(hash11(float(gl_VertexID) * 0.37), 3.0);
  float depthScale = uPointScale / max(clip.w, 0.6);
  gl_PointSize = clamp(depthScale * sizeVar * (0.55 + speed * 1.6), 1.5, 70.0);

  // Colour by velocity: slow dust reads cyan, fast plasma shifts magenta,
  // so the field visibly reports how agitated the system is.
  vec3 cold = vec3(0.20, 0.85, 1.00);
  vec3 hot = vec3(1.00, 0.32, 0.72);
  vColor = mix(cold, hot, speed * 0.9) + vec3(0.10, 0.05, 0.0) * uEnergy;
  // A minority of particles run violet, so the swarm has visible variety
  // instead of a single hue ramp.
  vColor = mix(vColor, vec3(0.62, 0.42, 1.0), step(0.88, hash11(float(gl_VertexID) * 0.017)));

  // Fade in over the first slice of life and out over the last, so nothing
  // ever pops into or out of existence.
  float fadeIn = smoothstep(0.0, 0.12, 1.0 - life);
  float fadeOut = smoothstep(0.0, 0.3, life);
  vAlpha = clamp(fadeIn * fadeOut, 0.0, 1.0) * (0.16 + 0.42 * speed) * uBoot;
}
`;

const RENDER_FRAG = /* glsl */ `#version 300 es
precision highp float;

in vec3 vColor;
in float vAlpha;
out vec4 fragColor;

void main() {
  vec2 d = gl_PointCoord * 2.0 - 1.0;
  float r2 = dot(d, d);
  if (r2 > 1.0) discard;
  // Tight core plus a wide halo — the profile a real light source has, and
  // what makes the bloom pass downstream look like glow rather than blur.
  float core = exp(-r2 * 4.0);
  float halo = exp(-r2 * 1.3) * 0.45;
  float a = (core + halo) * vAlpha;
  fragColor = vec4(vColor * (core * 2.2 + halo * 0.8), a);
}
`;

export class ParticleSystem {
  readonly stateful: boolean;
  private simSize: number;
  private count: number;

  private posTex: [WebGLTexture, WebGLTexture] | null = null;
  private velTex: [WebGLTexture, WebGLTexture] | null = null;
  private fbo: [WebGLFramebuffer, WebGLFramebuffer] | null = null;
  private targetTex: WebGLTexture | null = null;
  private hasTargets = false;
  private front = 0;

  private simProgram: Program | null = null;
  private readonly renderProgram: Program;
  private readonly emptyVao: WebGLVertexArrayObject;
  private readonly burstBuffer = new Float32Array(6 * 4);

  /** 0 = free swarm, 1 = fully assembled onto the target point cloud. */
  targetMix = 0;

  constructor(
    private readonly gl: WebGL2RenderingContext,
    private readonly quad: FullscreenTriangle,
    caps: GLCaps,
    simSize = 128
  ) {
    this.simSize = simSize;
    this.count = simSize * simSize;
    // Point rendering needs a bound VAO in WebGL2 even with zero attributes.
    this.emptyVao = gl.createVertexArray()!;

    // Allocate the solver first: an extension can be advertised and still
    // fail to produce a complete float MRT framebuffer, and the renderer
    // has to be compiled for whichever path actually survives.
    this.stateful = caps.floatRenderTargets && this.allocateState();
    if (this.stateful) {
      this.simProgram = new Program(gl, FULLSCREEN_VERT, SIM_FRAG, 'particles.sim');
    }

    this.renderProgram = new Program(
      gl,
      this.stateful ? RENDER_VERT : injectDefine(RENDER_VERT, 'ANALYTIC'),
      RENDER_FRAG,
      'particles.render'
    );
  }

  get particleCount() { return this.count; }

  /** Returns false if float MRT turns out to be unusable on this driver. */
  private allocateState(): boolean {
    const gl = this.gl;
    const n = this.simSize;
    const pos = new Float32Array(n * n * 4);
    const vel = new Float32Array(n * n * 4);
    for (let i = 0; i < n * n; i++) {
      const theta = Math.random() * Math.PI * 2;
      const rad = 2 + Math.random() * 12;
      pos[i * 4] = Math.cos(theta) * rad;
      pos[i * 4 + 1] = -4 + Math.random() * 15;
      pos[i * 4 + 2] = Math.sin(theta) * rad * 0.6;
      pos[i * 4 + 3] = Math.random(); // life, staggered so respawns never sync
      vel[i * 4 + 3] = Math.random(); // per-particle seed
    }

    this.posTex = [
      createDataTexture(gl, n, n, pos, true),
      createDataTexture(gl, n, n, pos, true),
    ];
    this.velTex = [
      createDataTexture(gl, n, n, vel, true),
      createDataTexture(gl, n, n, vel, true),
    ];
    this.fbo = [gl.createFramebuffer()!, gl.createFramebuffer()!];
    for (let i = 0; i < 2; i++) {
      gl.bindFramebuffer(gl.FRAMEBUFFER, this.fbo[i]);
      gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, this.posTex[i], 0);
      gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT1, gl.TEXTURE_2D, this.velTex[i], 0);
      gl.drawBuffers([gl.COLOR_ATTACHMENT0, gl.COLOR_ATTACHMENT1]);
      if (gl.checkFramebufferStatus(gl.FRAMEBUFFER) !== gl.FRAMEBUFFER_COMPLETE) {
        // Float MRT is advertised but not actually usable — fall back to the
        // analytic path rather than rendering a black screen.
        gl.bindFramebuffer(gl.FRAMEBUFFER, null);
        this.releaseState();
        return false;
      }
    }
    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
    return true;
  }

  private releaseState() {
    const gl = this.gl;
    this.posTex?.forEach((t) => gl.deleteTexture(t));
    this.velTex?.forEach((t) => gl.deleteTexture(t));
    this.fbo?.forEach((f) => gl.deleteFramebuffer(f));
    this.posTex = this.velTex = this.fbo = null;
  }

  /**
   * Supplies the point cloud the swarm assembles into. `points` is xyz
   * triples; it is tiled across the simulation texture so every particle
   * gets a destination even when there are more particles than points.
   */
  setTargets(points: Float32Array | null) {
    const gl = this.gl;
    if (!this.stateful) return;
    if (this.targetTex) { gl.deleteTexture(this.targetTex); this.targetTex = null; }
    if (!points || points.length < 3) { this.hasTargets = false; return; }

    const n = this.simSize;
    const data = new Float32Array(n * n * 4);
    const pointCount = Math.floor(points.length / 3);
    for (let i = 0; i < n * n; i++) {
      // Deterministic scatter rather than i % pointCount: a straight modulo
      // maps neighbouring particles to neighbouring points and the swarm
      // collapses in visible stripes.
      const j = (i * 2654435761) % pointCount;
      data[i * 4] = points[j * 3];
      data[i * 4 + 1] = points[j * 3 + 1];
      data[i * 4 + 2] = points[j * 3 + 2];
      data[i * 4 + 3] = 1;
    }
    this.targetTex = createDataTexture(gl, n, n, data, true);
    this.hasTargets = true;
  }

  simulate(dt: number, time: number, interaction: Interaction, pointerWorld: [number, number, number]) {
    if (!this.stateful || !this.simProgram || !this.fbo || !this.posTex || !this.velTex) return;
    const gl = this.gl;
    const src = this.front;
    const dst = 1 - this.front;

    const bursts = interaction.takeBursts();
    this.burstBuffer.fill(0);
    for (let i = 0; i < bursts.length && i < 6; i++) {
      this.burstBuffer[i * 4] = bursts[i].x;
      this.burstBuffer[i * 4 + 1] = bursts[i].y;
      this.burstBuffer[i * 4 + 2] = bursts[i].z;
      this.burstBuffer[i * 4 + 3] = bursts[i].strength;
    }

    gl.bindFramebuffer(gl.FRAMEBUFFER, this.fbo[dst]);
    gl.drawBuffers([gl.COLOR_ATTACHMENT0, gl.COLOR_ATTACHMENT1]);
    gl.viewport(0, 0, this.simSize, this.simSize);
    gl.disable(gl.DEPTH_TEST);
    gl.disable(gl.BLEND);

    const p = this.simProgram.use();
    p.tex('uPosTex', 0, this.posTex[src]);
    p.tex('uVelTex', 1, this.velTex[src]);
    p.tex('uTargetTex', 2, this.targetTex);
    p.f('uTime', time);
    p.f('uDt', dt);
    p.f('uEnergy', interaction.energy);
    p.f('uTargetMix', this.targetMix);
    p.f('uHasTargets', this.hasTargets ? 1 : 0);
    p.v3('uPointer', pointerWorld[0], pointerWorld[1], pointerWorld[2]);
    p.f('uPointerForce', 0.55 + interaction.pressAmount * 1.6);
    p.f('uBounds', 14);
    p.f('uFloorY', -4.0);
    gl.uniform4fv(p.loc('uBursts'), this.burstBuffer);

    this.quad.draw();
    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
    this.front = dst;
  }

  render(camera: Camera, time: number, interaction: Interaction, pointScale: number, boot: number) {
    const gl = this.gl;
    const p = this.renderProgram.use();
    if (this.stateful && this.posTex && this.velTex) {
      p.tex('uPosTex', 0, this.posTex[this.front]);
      p.tex('uVelTex', 1, this.velTex[this.front]);
    }
    p.m4('uViewProjection', camera.viewProjection);
    p.v2('uSimSize', this.simSize, this.simSize);
    p.f('uPointScale', pointScale);
    p.f('uTime', time);
    p.f('uEnergy', interaction.energy);
    p.f('uBoot', boot);

    gl.enable(gl.BLEND);
    // Additive: overlapping particles accumulate into hot cores, which is
    // exactly what the bloom pass is looking for.
    gl.blendFunc(gl.SRC_ALPHA, gl.ONE);
    gl.depthMask(false);
    gl.bindVertexArray(this.emptyVao);
    gl.drawArrays(gl.POINTS, 0, this.count);
    gl.bindVertexArray(null);
    gl.depthMask(true);
    gl.disable(gl.BLEND);
  }

  dispose() {
    this.releaseState();
    if (this.targetTex) this.gl.deleteTexture(this.targetTex);
    this.simProgram?.dispose();
    this.renderProgram.dispose();
    this.gl.deleteVertexArray(this.emptyVao);
  }
}

/** Inserts a #define straight after the #version line, where GLSL requires it. */
function injectDefine(source: string, name: string): string {
  const newline = source.indexOf('\n');
  return `${source.slice(0, newline + 1)}#define ${name} 1\n${source.slice(newline + 1)}`;
}
