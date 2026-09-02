import { Program } from './gl.js';
import { COLOR, NOISE, SDF } from './glsl.js';
import { compose, damp, mat4, type Mat4 } from './math.js';
import type { Camera } from './camera.js';

/**
 * Video feeds as holographic slabs.
 *
 * The stream is not shown as a rectangle of pixels — it is treated as the
 * emission of a light-field projector: luminance drives a parallax relief
 * so bright regions physically stand off the plate, the channels separate
 * toward the edges the way a real diffraction hologram does, and the whole
 * slab materialises out of a noise-threshold dissolve instead of fading in.
 */

const VERT = /* glsl */ `#version 300 es
precision highp float;

layout(location = 0) in vec2 aPos;

uniform mat4 uModel;
uniform mat4 uViewProjection;
uniform vec3 uCamPos;

out vec2 vUv;
out vec3 vWorld;
out vec3 vTangentView;   // view direction in the slab's tangent frame
out vec3 vNormal;

void main() {
  vec4 world = uModel * vec4(aPos, 0.0, 1.0);
  vWorld = world.xyz;
  vUv = aPos + 0.5;

  vec3 tangent = normalize(uModel[0].xyz);
  vec3 bitangent = normalize(uModel[1].xyz);
  vec3 normal = normalize(cross(tangent, bitangent));
  vNormal = normal;

  vec3 viewDir = normalize(uCamPos - world.xyz);
  vTangentView = vec3(dot(viewDir, tangent), dot(viewDir, bitangent), dot(viewDir, normal));

  gl_Position = uViewProjection * world;
}
`;

const FRAG = /* glsl */ `#version 300 es
precision highp float;

in vec2 vUv;
in vec3 vWorld;
in vec3 vTangentView;
in vec3 vNormal;
out vec4 fragColor;

uniform sampler2D uVideo;
uniform float uHasVideo;
uniform float uTime;
uniform float uMaterialize;   // 0 = scattered, 1 = solid
uniform float uFocus;         // 0 = idle, 1 = hovered/selected
uniform float uGlitch;
uniform float uRecording;
uniform float uAspectFix;     // video aspect / slab aspect, for cover fitting
uniform float uRelief;        // parallax relief depth
uniform vec3  uTint;
uniform float uSignal;        // 0 = no stream yet, 1 = live
uniform float uQuality;

${NOISE}
${SDF}
${COLOR}

vec3 sampleVideo(vec2 uv) {
  // Cover-fit: crop the long axis rather than squashing the picture.
  vec2 c = uv - 0.5;
  if (uAspectFix > 1.0) c.x /= uAspectFix; else c.y *= uAspectFix;
  vec2 t = c + 0.5;
  if (t.x < 0.0 || t.x > 1.0 || t.y < 0.0 || t.y > 1.0) return vec3(0.0);
  // Flip vertically: video frames arrive top-down, GL samples bottom-up.
  return texture(uVideo, vec2(t.x, 1.0 - t.y)).rgb;
}

float heightAt(vec2 uv) {
  return luma(sampleVideo(uv));
}

/**
 * Parallax occlusion mapping against the luminance heightfield: the picture
 * gains real depth that shifts as the camera moves, which is the single
 * effect that stops a video panel from reading as a flat sticker.
 */
vec2 parallaxUv(vec2 uv, vec3 tview, float depth, int steps) {
  if (depth <= 0.0001) return uv;
  vec2 delta = (tview.xy / max(abs(tview.z), 0.35)) * depth;
  float layerStep = 1.0 / float(steps);
  vec2 uvStep = delta * layerStep;

  float layerDepth = 0.0;
  vec2 cur = uv;
  float h = heightAt(cur);
  for (int i = 0; i < 12; i++) {
    if (i >= steps || layerDepth >= h) break;
    cur -= uvStep;
    h = heightAt(cur);
    layerDepth += layerStep;
  }
  // One linear refinement step: cheap, and removes the stair-stepping the
  // fixed-step march leaves behind.
  vec2 prev = cur + uvStep;
  float after = h - layerDepth;
  float before = heightAt(prev) - layerDepth + layerStep;
  float weight = after / (after - before + 1e-5);
  return mix(cur, prev, clamp(weight, 0.0, 1.0));
}

void main() {
  vec2 uv = vUv;
  vec2 centered = uv - 0.5;

  // --- dissolve mask -------------------------------------------------------
  // A noise field thresholded by the materialise parameter, so the slab
  // condenses out of the air in irregular flakes with a burning edge.
  float grain = fbm(vec3(uv * 7.5, uTime * 0.15), 4) * 0.5 + 0.5;
  float sweep = uv.y * 0.55 + uv.x * 0.15;
  float field = grain * 0.65 + sweep * 0.35;
  float threshold = 1.0 - uMaterialize;
  float solid = smoothstep(threshold - 0.02, threshold + 0.12, field);
  float burn = smoothstep(threshold - 0.10, threshold + 0.02, field) - solid;

  // --- relief-mapped video -------------------------------------------------
  int steps = int(mix(3.0, 12.0, uQuality));
  float relief = uRelief * (0.35 + 0.65 * uFocus) * uSignal;
  vec2 puv = parallaxUv(uv, normalize(vTangentView), relief, steps);

  // Channel separation grows toward the rim: diffraction, not a filter.
  float rim = length(centered) * 2.0;
  float split = (0.0018 + 0.0060 * rim * rim) * (1.0 + uGlitch * 6.0);
  vec3 video;
  video.r = sampleVideo(puv + vec2(split, 0.0)).r;
  video.g = sampleVideo(puv).g;
  video.b = sampleVideo(puv - vec2(split, 0.0)).b;
  video *= uHasVideo;

  // --- no-signal: a projector warming up, not a black rectangle ------------
  // A hex test lattice, a sync bar rolling through it and sparse static —
  // it reads as "waiting for the feed", not as a broken tile.
  vec4 hc = hexCoords(uv * 11.0);
  float hexEdge = smoothstep(0.455, 0.5, hexDist(hc.xy));
  float cellPulse = smoothstep(0.75, 1.0, sin(uTime * 2.2 + hash11(hc.z * 17.3 + hc.w * 5.1) * 6.28));
  float staticNoise = step(0.86, hash13(vec3(uv * 220.0, floor(uTime * 20.0))));
  float syncBar = exp(-abs(fract(uv.y + uTime * 0.35) - 0.5) * 14.0);

  vec3 noSignal = HOLO_CYAN * hexEdge * 0.06;
  noSignal += HOLO_MINT * cellPulse * (1.0 - hexEdge) * 0.05;
  noSignal += vec3(0.35, 0.75, 0.9) * staticNoise * 0.13;
  noSignal += HOLO_CYAN * syncBar * 0.12;
  // Hold the plate below unity before anything additive is layered on:
  // real camera feeds are often near-white, and a hologram that clips to a
  // flat sheet stops looking like light passing through anything.
  vec3 base = mix(noSignal, video, uSignal * uHasVideo) * 0.66;

  // --- holographic material ------------------------------------------------
  float lum = luma(base);

  // Interference fringes: thin spectral bands riding the luminance relief.
  float fringePhase = lum * 9.0 + uv.y * 26.0 - uTime * 0.7 + fbm(vec3(uv * 3.0, uTime * 0.1), 3) * 2.0;
  vec3 fringe = spectrum(fringePhase * 0.25) * 0.075 * (0.25 + lum);

  // Scanlines locked to the slab, not the screen, so they move with it in 3D.
  float scan = 0.5 + 0.5 * sin(uv.y * 460.0 - uTime * 9.0);
  float scanMask = mix(1.0, 0.82 + 0.18 * scan, 0.75);

  // Roll bar: a brighter band drifting down the plate.
  float roll = exp(-pow(fract(uv.y + uTime * 0.13) - 0.5, 2.0) * 40.0);

  vec3 col = base * scanMask;
  col = mix(col, col * uTint * 1.15, 0.62) + uTint * lum * 0.10;
  col += fringe;
  col += uTint * roll * 0.055;

  // Fresnel rim: the slab glows where it is seen edge-on.
  float fresnel = pow(1.0 - clamp(abs(vTangentView.z), 0.0, 1.0), 2.5);
  col += uTint * fresnel * (0.22 + 0.34 * uFocus);

  // --- frame, brackets and status ------------------------------------------
  vec2 half_ = vec2(0.5);
  float border = sdRoundBox(centered, half_ - 0.004, 0.012);
  float edgeLine = aaStroke(border, 0.0022);
  float bracket = aaStroke(sdBrackets(centered, half_ - 0.010, mix(0.10, 0.34, uFocus), 0.0), 0.0035);

  vec3 accent = mix(uTint, HOLO_MINT, uFocus * 0.5);
  col += accent * edgeLine * (0.40 + 0.40 * uFocus);
  col += accent * bracket * 1.15;

  // Recording tally: a pulsing band along the top edge.
  float tally = smoothstep(0.487, 0.5, abs(centered.y)) * step(0.0, centered.y);
  col += vec3(1.0, 0.25, 0.35) * tally * uRecording * (0.5 + 0.5 * sin(uTime * 5.0));

  // --- reconstruction scan while materialising -----------------------------
  float scanY = fract(uMaterialize * 1.3);
  float reconstruct = exp(-abs(uv.y - scanY) * 60.0) * (1.0 - smoothstep(0.85, 1.0, uMaterialize));
  col += HOLO_MINT * reconstruct * 1.3;

  // --- glitch: horizontal block displacement of the composite --------------
  if (uGlitch > 0.001) {
    float band = floor(uv.y * 26.0);
    float jitter = (hash11(band + floor(uTime * 24.0)) - 0.5) * uGlitch * 0.12;
    vec2 guv = vec2(clamp(uv.x + jitter, 0.0, 1.0), uv.y);
    vec3 shifted = sampleVideo(guv) * uHasVideo;
    col = mix(col, shifted * uTint * 1.6 + vec3(0.0, 0.25, 0.35), uGlitch * 0.55);
  }

  // --- assemble alpha ------------------------------------------------------
  col += mix(HOLO_CYAN, HOLO_MAGENTA, 0.35) * burn * 1.9;

  float alpha = solid * (0.90 + 0.10 * uFocus);
  alpha = max(alpha, burn * 0.9);
  // Never fully opaque: light passes through a hologram.
  alpha *= 0.94;

  if (alpha < 0.004) discard;
  fragColor = vec4(col * alpha, alpha);
}
`;

export interface PanelInit {
  id: string;
  video?: HTMLVideoElement;
  tint?: [number, number, number];
}

/**
 * One slab. Every visual property is a spring target rather than a value:
 * nothing in this interface jumps, and layout changes are absorbed as
 * motion.
 */
export class Panel {
  readonly id: string;
  video: HTMLVideoElement | null;
  texture: WebGLTexture | null = null;

  // Current values.
  x = 0; y = 0; z = 0;
  width = 6; height = 3.4;
  rotX = 0; rotY = 0; rotZ = 0;
  materialize = 0;
  focus = 0;
  glitch = 0;
  recording = 0;

  // Spring targets.
  tx = 0; ty = 0; tz = 0;
  tWidth = 6; tHeight = 3.4;
  tRotX = 0; tRotY = 0; tRotZ = 0;
  tMaterialize = 1;
  tFocus = 0;
  tRecording = 0;

  /** Velocity accumulators for the position spring. */
  private vx = 0; private vy = 0; private vz = 0;

  tint: [number, number, number];
  videoAspect = 16 / 9;
  hasFrame = false;
  readonly model: Mat4 = mat4();

  constructor(init: PanelInit) {
    this.id = init.id;
    this.video = init.video ?? null;
    this.tint = init.tint ?? [0.25, 0.9, 1.0];
  }

  /** Underdamped spring on position, damped exponential on everything else. */
  update(dt: number) {
    const stiffness = 120;
    const damping = 17;
    const step = (pos: number, vel: number, target: number) => {
      const accel = (target - pos) * stiffness - vel * damping;
      const nv = vel + accel * dt;
      return [pos + nv * dt, nv] as const;
    };
    [this.x, this.vx] = step(this.x, this.vx, this.tx);
    [this.y, this.vy] = step(this.y, this.vy, this.ty);
    [this.z, this.vz] = step(this.z, this.vz, this.tz);

    this.width = damp(this.width, this.tWidth, 9, dt);
    this.height = damp(this.height, this.tHeight, 9, dt);
    this.rotX = damp(this.rotX, this.tRotX, 7, dt);
    this.rotY = damp(this.rotY, this.tRotY, 7, dt);
    this.rotZ = damp(this.rotZ, this.tRotZ, 7, dt);
    this.materialize = damp(this.materialize, this.tMaterialize, 3.2, dt);
    this.focus = damp(this.focus, this.tFocus, 8, dt);
    this.recording = damp(this.recording, this.tRecording, 6, dt);
    this.glitch = Math.max(0, this.glitch - dt * 2.4);

    compose(this.model, this.x, this.y, this.z, this.rotX, this.rotY, this.rotZ, this.width, this.height, 1);
  }

  /** True once the dematerialise animation has finished and it can be dropped. */
  get finished() { return this.tMaterialize <= 0 && this.materialize < 0.01; }
}

export class PanelPass {
  private readonly program: Program;
  private readonly vao: WebGLVertexArrayObject;
  readonly panels: Panel[] = [];

  constructor(private readonly gl: WebGL2RenderingContext) {
    this.program = new Program(gl, VERT, FRAG, 'panel');
    this.vao = gl.createVertexArray()!;
    const buffer = gl.createBuffer()!;
    gl.bindVertexArray(this.vao);
    gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
    gl.bufferData(
      gl.ARRAY_BUFFER,
      new Float32Array([-0.5, -0.5, 0.5, -0.5, -0.5, 0.5, 0.5, -0.5, 0.5, 0.5, -0.5, 0.5]),
      gl.STATIC_DRAW
    );
    gl.enableVertexAttribArray(0);
    gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
    gl.bindVertexArray(null);
  }

  add(panel: Panel): Panel {
    this.panels.push(panel);
    if (panel.video) panel.texture = this.gl.createTexture();
    return panel;
  }

  remove(panel: Panel) {
    const i = this.panels.indexOf(panel);
    if (i >= 0) this.panels.splice(i, 1);
    if (panel.texture) this.gl.deleteTexture(panel.texture);
    panel.texture = null;
  }

  update(dt: number) {
    for (const p of this.panels) p.update(dt);
  }

  private uploadVideo(panel: Panel) {
    const gl = this.gl;
    const video = panel.video;
    if (!video || !panel.texture) return;
    if (video.readyState < 2 || video.videoWidth === 0) return;
    panel.videoAspect = video.videoWidth / video.videoHeight;
    gl.bindTexture(gl.TEXTURE_2D, panel.texture);
    if (!panel.hasFrame) {
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
      panel.hasFrame = true;
    }
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, video);
  }

  render(camera: Camera, time: number, quality: number) {
    const gl = this.gl;
    if (this.panels.length === 0) return;

    // Back-to-front: these are translucent and blended, so depth-sorting is
    // what keeps overlapping slabs layering correctly.
    const sorted = [...this.panels].sort((a, b) => {
      const da = (a.x - camera.position[0]) ** 2 + (a.y - camera.position[1]) ** 2 + (a.z - camera.position[2]) ** 2;
      const db = (b.x - camera.position[0]) ** 2 + (b.y - camera.position[1]) ** 2 + (b.z - camera.position[2]) ** 2;
      return db - da;
    });

    const p = this.program.use();
    p.m4('uViewProjection', camera.viewProjection);
    p.v3('uCamPos', camera.position[0], camera.position[1], camera.position[2]);
    p.f('uTime', time);
    p.f('uQuality', quality);

    gl.bindVertexArray(this.vao);
    gl.enable(gl.BLEND);
    // Premultiplied alpha: the shader already multiplies colour by alpha, so
    // the burning dissolve edge stays additive-bright without haloing.
    gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
    gl.enable(gl.DEPTH_TEST);
    gl.depthMask(false);

    for (const panel of sorted) {
      this.uploadVideo(panel);
      const slabAspect = panel.width / panel.height;
      p.m4('uModel', panel.model);
      p.tex('uVideo', 0, panel.texture);
      p.f('uHasVideo', panel.hasFrame ? 1 : 0);
      p.f('uMaterialize', panel.materialize);
      p.f('uFocus', panel.focus);
      p.f('uGlitch', panel.glitch);
      p.f('uRecording', panel.recording);
      p.f('uSignal', panel.hasFrame ? 1 : 0);
      p.f('uAspectFix', panel.videoAspect / slabAspect);
      p.f('uRelief', 0.045);
      p.v3('uTint', panel.tint[0], panel.tint[1], panel.tint[2]);
      gl.drawArrays(gl.TRIANGLES, 0, 6);
    }

    gl.depthMask(true);
    gl.disable(gl.DEPTH_TEST);
    gl.disable(gl.BLEND);
    gl.bindVertexArray(null);
  }

  dispose() {
    this.program.dispose();
    this.gl.deleteVertexArray(this.vao);
    for (const panel of this.panels) if (panel.texture) this.gl.deleteTexture(panel.texture);
  }
}
