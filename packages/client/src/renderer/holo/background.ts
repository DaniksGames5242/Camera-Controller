import { FULLSCREEN_VERT, Program, type FullscreenTriangle } from './gl.js';
import { COLOR, NOISE, SDF } from './glsl.js';
import type { Camera } from './camera.js';
import type { Interaction } from './interaction.js';

/**
 * The stage the whole interface stands on: a raymarched volumetric chamber
 * with an infinite hex floor, drifting nebula fog, a lidar scan plane that
 * sweeps the room, and ripple rings emitted by whatever the user just
 * touched.
 *
 * It is one fullscreen fragment shader — no geometry — so the "room" costs
 * exactly one draw call and can be quality-scaled by lowering the march
 * step count alone.
 */

const FRAG = /* glsl */ `#version 300 es
precision highp float;

in vec2 vUv;
out vec4 fragColor;

uniform vec2  uResolution;
uniform float uTime;
uniform vec3  uCamPos;
uniform vec3  uCamRight;
uniform vec3  uCamUp;
uniform vec3  uCamFwd;
uniform float uTanHalfFov;
uniform float uAspect;
uniform vec2  uPointer;        // -1..1 screen space
uniform float uEnergy;         // 0..1 global excitement, driven by app events
uniform float uBoot;           // 0..1 boot-sequence progress
uniform float uSteps;          // volumetric march steps (quality dial)
uniform vec4  uRipples[8];     // xz origin on the floor, birth time, strength
uniform float uScanPhase;      // rising lidar plane, in world units
uniform float uReduceMotion;

${NOISE}
${SDF}
${COLOR}

const float FLOOR_Y = -4.0;

// ---------------------------------------------------------------------------
// Floor: hex lattice + concentric ripples + a slow radial sweep.
// ---------------------------------------------------------------------------
vec3 floorShade(vec3 p, float dist) {
  vec2 uv = p.xz * 0.55;

  vec4 hc = hexCoords(uv);
  float cell = hexDist(hc.xy);
  // Edge glow rather than a filled tile: the lattice should read as light
  // caught on the seams of the floor, not as a mosaic of opaque plates.
  float seam = smoothstep(0.455, 0.5, cell);
  float seamCore = smoothstep(0.489, 0.5, cell);

  // Per-cell activity: a slow travelling wave picks out cells to light up,
  // and even then only fills them faintly.
  float cellSeed = hash11(hc.z * 37.13 + hc.w * 91.7);
  float pulse = sin(uTime * 1.1 + cellSeed * 6.28318 + length(hc.zw) * 0.35);
  float lit_cell = smoothstep(0.90, 1.0, pulse) * (0.25 + 0.75 * uEnergy);

  // Rectangular sub-grid underneath, for a second scale of detail.
  vec2 g = abs(fract(p.xz * 0.25) - 0.5);
  float grid = smoothstep(0.492, 0.5, max(g.x, g.y));

  float rings = 0.0;
  for (int i = 0; i < 8; i++) {
    vec4 r = uRipples[i];
    if (r.w <= 0.0) continue;
    float age = uTime - r.z;
    if (age < 0.0 || age > 3.0) continue;
    float d = length(p.xz - r.xy);
    float radius = age * 7.0;
    float ring = exp(-abs(d - radius) * 1.6) * exp(-age * 1.35);
    rings += ring * r.w;
  }

  // Distance fade plus a grazing-angle fade, so the lattice dissolves into
  // the haze instead of aliasing into moire at the horizon.
  float fade = exp(-dist * 0.030);

  // Hue drifts across the floor so the room is never one flat colour.
  vec3 tint = mix(HOLO_CYAN, HOLO_VIOLET, smoothstep(-18.0, 18.0, p.x) * 0.7);
  tint = mix(tint, HOLO_MINT, smoothstep(20.0, -20.0, p.z) * 0.25);

  vec3 col = tint * (seam * 0.13 + seamCore * 0.55 + grid * 0.13);
  col += HOLO_MINT * lit_cell * (1.0 - seam) * 0.16;
  col += mix(HOLO_CYAN, HOLO_MAGENTA, 0.5) * rings * 1.8;

  // Lidar plane crossing the floor leaves a bright wavefront behind it.
  float scan = exp(-abs(p.z - uScanPhase) * 1.4) * 0.5;
  col += HOLO_VIOLET * scan * (0.4 + uEnergy);

  return col * fade;
}

// ---------------------------------------------------------------------------
// Volumetric medium: nebula fbm modulated by height, plus light shafts from
// the emitter above the stage.
// ---------------------------------------------------------------------------
float mediumDensity(vec3 p) {
  vec3 q = p * 0.14;
  q.y *= 1.6;
  q += vec3(uTime * 0.035, uTime * -0.02, uTime * 0.045);
  float n = fbm(q, 4);
  float body = smoothstep(-0.15, 0.55, n);
  // Confine the haze to the lower half of the room so the horizon reads.
  float height = exp(-max(0.0, p.y - FLOOR_Y) * 0.22);
  return body * height;
}

void main() {
  vec2 uv = vUv * 2.0 - 1.0;
  vec3 rd = normalize(uCamFwd + uCamRight * uv.x * uTanHalfFov * uAspect + uCamUp * uv.y * uTanHalfFov);
  vec3 ro = uCamPos;

  vec3 col = vec3(0.0);

  // --- deep void gradient + distant dust -----------------------------------
  float horizon = smoothstep(-0.35, 0.65, rd.y);
  col += mix(vec3(0.008, 0.020, 0.035), vec3(0.004, 0.008, 0.020), horizon);
  col += HOLO_VIOLET * 0.030 * pow(1.0 - abs(rd.y), 6.0);

  // Star/dust field, stable in world space so it parallaxes with the camera.
  // High frequency and a hard power curve keep them as points rather than
  // the soft blobs a low-frequency noise gives.
  vec3 sdir = rd * 130.0;
  float starField = max(0.0, gnoise(sdir));
  // Thin the field with a per-cell mask: a pure power curve leaves an even
  // dusting everywhere, which reads as noise rather than as a sky.
  float starMask = step(0.62, hash13(floor(sdir)));
  float stars = pow(starField, 26.0) * 1400.0 * starMask;
  float twinkle = 0.5 + 0.5 * sin(uTime * 2.6 + starField * 220.0);
  col += mix(vec3(0.55, 0.85, 1.0), HOLO_MAGENTA, step(0.88, hash13(floor(sdir) + 7.0))) * stars * twinkle;

  // --- floor ---------------------------------------------------------------
  float tFloor = 1e9;
  if (rd.y < -0.0001) {
    float t = (FLOOR_Y - ro.y) / rd.y;
    if (t > 0.0) tFloor = t;
  }
  if (tFloor < 400.0) {
    vec3 hit = ro + rd * tFloor;
    col += floorShade(hit, tFloor);
  }

  // --- volumetric march ----------------------------------------------------
  float far = min(tFloor, 90.0);
  int steps = int(clamp(uSteps, 6.0, 48.0));
  float stepLen = far / float(steps);
  // Dither the first sample to trade banding for a little noise the grain
  // pass then hides completely.
  float jitter = hash13(vec3(gl_FragCoord.xy, uTime * 60.0));
  float t = stepLen * jitter;
  vec3 fog = vec3(0.0);
  float transmittance = 1.0;

  for (int i = 0; i < 48; i++) {
    if (i >= steps || transmittance < 0.02) break;
    vec3 p = ro + rd * t;
    float d = mediumDensity(p) * 0.055;
    if (d > 0.001) {
      // Emitter cone: a shaft of light pouring down the stage axis.
      float axial = length(p.xz);
      float shaft = exp(-axial * axial * 0.010) * smoothstep(-14.0, 8.0, p.y);
      // Data filaments: thin vertical beams standing in fixed floor cells,
      // scrolling upward. Cheap, and they give the volume real structure.
      vec2 cellId = floor(p.xz * 0.22);
      float beamSeed = hash11(cellId.x * 13.7 + cellId.y * 71.3);
      vec2 cellUv = fract(p.xz * 0.22) - 0.5;
      float beam = step(0.955, beamSeed) * exp(-dot(cellUv, cellUv) * 90.0)
                 * (0.5 + 0.5 * sin(p.y * 3.0 - uTime * 6.0 + beamSeed * 40.0));

      vec3 lit = mix(HOLO_CYAN, HOLO_VIOLET, smoothstep(-4.0, 8.0, p.y)) * 0.30;
      lit += HOLO_MAGENTA * shaft * 0.28;
      lit += HOLO_MINT * beam * 1.1;

      // Emission-absorption integral. Accumulating density times stepLen
      // directly is unbounded — with a long ray and a dense medium it
      // saturates to white — whereas weighting by the segment's own
      // absorption keeps the result bounded by the brightest sample.
      float absorb = 1.0 - exp(-d * stepLen);
      fog += lit * absorb * transmittance;
      transmittance *= 1.0 - absorb;
    }
    t += stepLen;
  }
  col += fog;

  // --- pointer aura: the cursor genuinely lights the medium ---------------
  vec2 pd = (uv - uPointer) * vec2(uAspect, 1.0);
  float aura = exp(-dot(pd, pd) * 9.0);
  col += mix(HOLO_CYAN, HOLO_MINT, 0.5) * aura * (0.022 + 0.055 * uEnergy);

  // --- boot: the room powers on from a single point of light --------------
  float bootMask = mix(smoothstep(0.0, 0.9, uBoot), 1.0, uReduceMotion);
  float iris = smoothstep(1.35, 0.0, length(uv) - uBoot * 1.6);
  col *= bootMask * mix(iris, 1.0, smoothstep(0.7, 1.0, uBoot));
  col += HOLO_CYAN * exp(-length(uv) * 9.0) * (1.0 - smoothstep(0.0, 0.55, uBoot)) * 3.0;

  fragColor = vec4(col, 1.0);
}
`;

export class BackgroundPass {
  private readonly program: Program;
  private readonly ripples = new Float32Array(8 * 4);

  constructor(private readonly gl: WebGL2RenderingContext, private readonly quad: FullscreenTriangle) {
    this.program = new Program(gl, FULLSCREEN_VERT, FRAG, 'background');
  }

  render(camera: Camera, interaction: Interaction, time: number, quality: number, boot: number, reduceMotion: boolean) {
    const gl = this.gl;
    const p = this.program.use();

    interaction.writeRipples(this.ripples);

    p.v2('uResolution', gl.drawingBufferWidth, gl.drawingBufferHeight);
    p.f('uTime', time);
    p.v3('uCamPos', camera.position[0], camera.position[1], camera.position[2]);
    p.v3('uCamRight', camera.right[0], camera.right[1], camera.right[2]);
    p.v3('uCamUp', camera.upVector[0], camera.upVector[1], camera.upVector[2]);
    p.v3('uCamFwd', camera.forward[0], camera.forward[1], camera.forward[2]);
    p.f('uTanHalfFov', Math.tan(camera.fov / 2));
    p.f('uAspect', camera.aspect);
    p.v2('uPointer', interaction.ndcX, interaction.ndcY);
    p.f('uEnergy', interaction.energy);
    p.f('uBoot', boot);
    p.f('uSteps', 10 + quality * 30);
    p.f('uScanPhase', interaction.scanPhase);
    p.f('uReduceMotion', reduceMotion ? 1 : 0);
    gl.uniform4fv(p.loc('uRipples'), this.ripples);

    gl.disable(gl.DEPTH_TEST);
    gl.disable(gl.BLEND);
    this.quad.draw();
  }

  dispose() { this.program.dispose(); }
}
