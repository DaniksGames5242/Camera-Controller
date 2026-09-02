import { FULLSCREEN_VERT, Program, RenderTarget, type FullscreenTriangle, type GLCaps } from './gl.js';
import { COLOR, NOISE } from './glsl.js';
import type { Interaction } from './interaction.js';

/**
 * The projector optics.
 *
 * Everything that makes the image read as light thrown through air rather
 * than pixels on glass happens here: energy bleeding out of bright cores,
 * the lens splitting the spectrum toward the corners, the barrel of the
 * emitter curving the frame, an aperture grille, and datamosh tearing on
 * events. It is deliberately one chain over the whole composited scene, so
 * the chrome and the video are subject to identical optics and read as one
 * projection.
 */

const THRESHOLD_FRAG = /* glsl */ `#version 300 es
precision highp float;
in vec2 vUv;
out vec4 fragColor;
uniform sampler2D uScene;
uniform vec2 uTexel;
uniform float uThreshold;
uniform float uKnee;

${COLOR}

void main() {
  // 4-tap box downsample first: halves the resolution without the shimmer
  // a single point sample gives on thin bright lines.
  vec3 c = texture(uScene, vUv + vec2(-uTexel.x, -uTexel.y)).rgb;
  c += texture(uScene, vUv + vec2(uTexel.x, -uTexel.y)).rgb;
  c += texture(uScene, vUv + vec2(-uTexel.x, uTexel.y)).rgb;
  c += texture(uScene, vUv + vec2(uTexel.x, uTexel.y)).rgb;
  c *= 0.25;

  // Soft knee so the bloom ramps in instead of popping at the threshold.
  float br = max(c.r, max(c.g, c.b));
  float soft = clamp(br - uThreshold + uKnee, 0.0, 2.0 * uKnee);
  soft = soft * soft / (4.0 * uKnee + 1e-5);
  float contribution = max(soft, br - uThreshold) / max(br, 1e-5);
  fragColor = vec4(c * contribution, 1.0);
}
`;

const BLUR_FRAG = /* glsl */ `#version 300 es
precision highp float;
in vec2 vUv;
out vec4 fragColor;
uniform sampler2D uSource;
uniform vec2 uDirection;   // texel-sized step along one axis

void main() {
  // 9-tap gaussian collapsed to 5 bilinear fetches.
  vec3 sum = texture(uSource, vUv).rgb * 0.227027;
  vec2 o1 = uDirection * 1.3846153846;
  vec2 o2 = uDirection * 3.2307692308;
  sum += texture(uSource, vUv + o1).rgb * 0.3162162162;
  sum += texture(uSource, vUv - o1).rgb * 0.3162162162;
  sum += texture(uSource, vUv + o2).rgb * 0.0702702703;
  sum += texture(uSource, vUv - o2).rgb * 0.0702702703;
  fragColor = vec4(sum, 1.0);
}
`;

const COMPOSITE_FRAG = /* glsl */ `#version 300 es
precision highp float;
in vec2 vUv;
out vec4 fragColor;

uniform sampler2D uScene;
uniform sampler2D uBloomA;
uniform sampler2D uBloomB;
uniform vec2  uResolution;
uniform float uTime;
uniform float uGlitch;
uniform float uEnergy;
uniform float uBoot;
uniform float uBloomStrength;
uniform float uAberration;
uniform float uReduceMotion;
uniform vec2  uPointer;

${NOISE}
${COLOR}

// Barrel distortion of the emitter optics — subtle, but it is what makes
// the frame feel like a projected volume with edges rather than a window.
vec2 barrel(vec2 uv, float amount) {
  vec2 c = uv - 0.5;
  float r2 = dot(c, c);
  return 0.5 + c * (1.0 + amount * r2);
}

void main() {
  vec2 uv = vUv;

  // --- datamosh: whole horizontal bands tear sideways -----------------------
  float glitch = uGlitch * (1.0 - uReduceMotion);
  if (glitch > 0.001) {
    float band = floor(uv.y * 34.0 + uTime * 2.0);
    float roll = hash11(band + floor(uTime * 18.0));
    float bandOn = step(0.72 - glitch * 0.4, roll);
    uv.x += (roll - 0.5) * glitch * 0.09 * bandOn;
    uv.y += (hash11(band * 3.7) - 0.5) * glitch * 0.012 * bandOn;
  }

  uv = barrel(uv, 0.035 + 0.02 * uEnergy);

  vec2 toCenter = uv - 0.5;
  float r = length(toCenter);

  // --- chromatic aberration: radial, quadratic, glitch-amplified -----------
  float ab = uAberration * (0.0016 + r * r * 0.010) * (1.0 + glitch * 5.0);
  vec2 dir = r > 1e-5 ? toCenter / r : vec2(0.0);
  vec3 scene;
  scene.r = texture(uScene, uv + dir * ab).r;
  scene.g = texture(uScene, uv).g;
  scene.b = texture(uScene, uv - dir * ab).b;

  vec3 bloom = texture(uBloomA, uv).rgb * 0.62 + texture(uBloomB, uv).rgb * 0.38;
  // Bloom picks up its own, wider chromatic spread — cheap "lens glass".
  bloom.r = texture(uBloomA, uv + dir * ab * 2.2).r * 0.62 + texture(uBloomB, uv + dir * ab * 2.6).r * 0.38;
  bloom.b = texture(uBloomA, uv - dir * ab * 2.2).b * 0.62 + texture(uBloomB, uv - dir * ab * 2.6).b * 0.38;

  vec3 col = scene + bloom * uBloomStrength;

  // --- aperture grille -----------------------------------------------------
  // Per-subpixel column mask plus a rolling horizontal scan; kept gentle so
  // it never fights the video for legibility.
  float px = uv.x * uResolution.x;
  vec3 grille = vec3(
    0.5 + 0.5 * sin(px * 3.14159),
    0.5 + 0.5 * sin(px * 3.14159 + 2.094),
    0.5 + 0.5 * sin(px * 3.14159 + 4.188)
  );
  col *= mix(vec3(1.0), 0.78 + 0.22 * grille, 0.30);

  float scanline = 0.5 + 0.5 * sin(uv.y * uResolution.y * 1.7 - uTime * 3.0);
  col *= mix(1.0, 0.88 + 0.12 * scanline, 0.35);

  float sweep = fract(uv.y * 0.5 - uTime * 0.07);
  col += vec3(0.05, 0.14, 0.18) * exp(-sweep * 12.0) * (0.4 + uEnergy);

  // --- pointer light bloom -------------------------------------------------
  vec2 pd = (uv - (uPointer * 0.5 + 0.5)) * vec2(uResolution.x / uResolution.y, 1.0);
  col += mix(HOLO_CYAN, HOLO_MINT, 0.4) * exp(-dot(pd, pd) * 90.0) * 0.35;

  // --- vignette + edge flicker ---------------------------------------------
  float vig = smoothstep(1.05, 0.28, r);
  col *= mix(0.35, 1.0, vig);
  col *= 0.97 + 0.03 * sin(uTime * 47.0) * (1.0 - uReduceMotion);

  // --- boot iris -----------------------------------------------------------
  col *= smoothstep(0.0, 0.35, uBoot);

  col = tonemapACES(col * 0.92);

  // --- grain: applied after tone mapping so it survives into the blacks ----
  float grain = hash13(vec3(gl_FragCoord.xy, uTime * 91.0)) - 0.5;
  col += grain * (0.028 + 0.02 * (1.0 - luma(col)));

  // Off-frame after barrel distortion reads as the edge of the projection.
  if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) col *= 0.0;

  fragColor = vec4(col, 1.0);
}
`;

export class PostChain {
  scene: RenderTarget;
  private bloomA: RenderTarget;
  private bloomB: RenderTarget;
  private bloomTemp: RenderTarget;
  private bloomTempB: RenderTarget;

  private readonly threshold: Program;
  private readonly blur: Program;
  private readonly composite: Program;

  constructor(
    private readonly gl: WebGL2RenderingContext,
    private readonly quad: FullscreenTriangle,
    caps: GLCaps,
    width: number,
    height: number
  ) {
    const float = caps.floatRenderTargets;
    this.scene = new RenderTarget(gl, width, height, { float, depth: true });
    this.bloomA = new RenderTarget(gl, width >> 1, height >> 1, { float });
    this.bloomTemp = new RenderTarget(gl, width >> 1, height >> 1, { float });
    this.bloomB = new RenderTarget(gl, width >> 2, height >> 2, { float });
    this.bloomTempB = new RenderTarget(gl, width >> 2, height >> 2, { float });

    this.threshold = new Program(gl, FULLSCREEN_VERT, THRESHOLD_FRAG, 'post.threshold');
    this.blur = new Program(gl, FULLSCREEN_VERT, BLUR_FRAG, 'post.blur');
    this.composite = new Program(gl, FULLSCREEN_VERT, COMPOSITE_FRAG, 'post.composite');
  }

  resize(width: number, height: number) {
    this.scene.resize(width, height);
    this.bloomA.resize(width >> 1, height >> 1);
    this.bloomTemp.resize(width >> 1, height >> 1);
    this.bloomB.resize(width >> 2, height >> 2);
    this.bloomTempB.resize(width >> 2, height >> 2);
  }

  /** Binds the offscreen scene buffer; everything drawn after lands in it. */
  beginScene() {
    const gl = this.gl;
    this.scene.bind();
    gl.clearColor(0, 0, 0, 1);
    gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT);
  }

  private blurPass(source: RenderTarget, temp: RenderTarget, dest: RenderTarget, radius: number) {
    const p = this.blur.use();
    temp.bind();
    p.tex('uSource', 0, source.texture);
    p.v2('uDirection', radius / temp.width, 0);
    this.quad.draw();

    dest.bind();
    p.tex('uSource', 0, temp.texture);
    p.v2('uDirection', 0, radius / dest.height);
    this.quad.draw();
  }

  /** Resolves the scene buffer to the canvas with the full optics chain. */
  finish(canvasWidth: number, canvasHeight: number, time: number, interaction: Interaction, boot: number, reduceMotion: boolean) {
    const gl = this.gl;
    gl.disable(gl.DEPTH_TEST);
    gl.disable(gl.BLEND);

    // Bright pass into the half-res buffer.
    this.bloomA.bind();
    const t = this.threshold.use();
    t.tex('uScene', 0, this.scene.texture);
    t.v2('uTexel', 1 / this.scene.width, 1 / this.scene.height);
    t.f('uThreshold', 0.82);
    t.f('uKnee', 0.26);
    this.quad.draw();

    this.blurPass(this.bloomA, this.bloomTemp, this.bloomA, 1.4);

    // Quarter-res chain gives the wide, soft halo the tight blur can't.
    this.bloomB.bind();
    const t2 = this.threshold.use();
    t2.tex('uScene', 0, this.bloomA.texture);
    t2.v2('uTexel', 1 / this.bloomA.width, 1 / this.bloomA.height);
    t2.f('uThreshold', 0.0);
    t2.f('uKnee', 0.25);
    this.quad.draw();
    this.blurPass(this.bloomB, this.bloomTempB, this.bloomB, 2.6);

    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
    gl.viewport(0, 0, canvasWidth, canvasHeight);
    const c = this.composite.use();
    c.tex('uScene', 0, this.scene.texture);
    c.tex('uBloomA', 1, this.bloomA.texture);
    c.tex('uBloomB', 2, this.bloomB.texture);
    c.v2('uResolution', canvasWidth, canvasHeight);
    c.f('uTime', time);
    c.f('uGlitch', Math.min(1, interaction.glitch));
    c.f('uEnergy', interaction.energy);
    c.f('uBoot', boot);
    c.f('uBloomStrength', 0.60);
    c.f('uAberration', reduceMotion ? 0.35 : 1);
    c.f('uReduceMotion', reduceMotion ? 1 : 0);
    c.v2('uPointer', interaction.smoothX, interaction.smoothY);
    this.quad.draw();
  }

  dispose() {
    this.scene.dispose();
    this.bloomA.dispose();
    this.bloomB.dispose();
    this.bloomTemp.dispose();
    this.bloomTempB.dispose();
    this.threshold.dispose();
    this.blur.dispose();
    this.composite.dispose();
  }
}
