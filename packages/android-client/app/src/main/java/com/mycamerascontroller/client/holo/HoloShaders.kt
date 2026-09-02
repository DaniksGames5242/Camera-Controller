package com.mycamerascontroller.client.holo

/**
 * The shader library behind the Android hologram.
 *
 * Same visual language as the desktop client — one noise basis, one palette,
 * one set of optics — but re-authored for a phone: fewer march steps, mediump
 * throughout, no floating-point render targets, and every effect that costs
 * more than it shows on a 6-inch screen at arm's length has been dropped.
 *
 * What replaces them is the thing a phone has and a desktop does not: the
 * device's own orientation. Tilt drives the parallax of the whole volume, so
 * the hologram behaves like something sitting behind the glass rather than
 * something painted on it.
 */

const val COMMON = """
precision highp float;

float hash11(float p) {
  p = fract(p * 0.1031);
  p *= p + 33.33;
  p *= p + p;
  return fract(p);
}

float hash13(vec3 p) {
  p = fract(p * 0.1031);
  p += dot(p, p.zyx + 31.32);
  return fract((p.x + p.y) * p.z);
}

vec3 hash33(vec3 p) {
  p = vec3(dot(p, vec3(127.1, 311.7, 74.7)),
           dot(p, vec3(269.5, 183.3, 246.1)),
           dot(p, vec3(113.5, 271.9, 124.6)));
  return fract(sin(p) * 43758.5453123) * 2.0 - 1.0;
}

float gnoise(vec3 p) {
  vec3 i = floor(p);
  vec3 f = fract(p);
  vec3 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
  return mix(
    mix(mix(dot(hash33(i + vec3(0.0, 0.0, 0.0)), f - vec3(0.0, 0.0, 0.0)),
            dot(hash33(i + vec3(1.0, 0.0, 0.0)), f - vec3(1.0, 0.0, 0.0)), u.x),
        mix(dot(hash33(i + vec3(0.0, 1.0, 0.0)), f - vec3(0.0, 1.0, 0.0)),
            dot(hash33(i + vec3(1.0, 1.0, 0.0)), f - vec3(1.0, 1.0, 0.0)), u.x), u.y),
    mix(mix(dot(hash33(i + vec3(0.0, 0.0, 1.0)), f - vec3(0.0, 0.0, 1.0)),
            dot(hash33(i + vec3(1.0, 0.0, 1.0)), f - vec3(1.0, 0.0, 1.0)), u.x),
        mix(dot(hash33(i + vec3(0.0, 1.0, 1.0)), f - vec3(0.0, 1.0, 1.0)),
            dot(hash33(i + vec3(1.0, 1.0, 1.0)), f - vec3(1.0, 1.0, 1.0)), u.x), u.y),
    u.z);
}

float fbm3(vec3 p) {
  float sum = 0.0;
  float amp = 0.5;
  mat3 rot = mat3(0.00, 0.80, 0.60, -0.80, 0.36, -0.48, -0.60, -0.48, 0.64);
  for (int i = 0; i < 3; i++) {
    sum += amp * gnoise(p);
    p = rot * p * 2.03;
    amp *= 0.5;
  }
  return sum;
}

vec3 curlPotential(vec3 p) {
  return vec3(
    gnoise(p),
    gnoise(p + vec3(31.416, 17.713, 5.219)),
    gnoise(p + vec3(-19.137, 7.331, 42.881))
  );
}

vec3 curlNoise(vec3 p) {
  const float e = 0.32;
  vec3 x0 = curlPotential(p - vec3(e, 0.0, 0.0));
  vec3 x1 = curlPotential(p + vec3(e, 0.0, 0.0));
  vec3 y0 = curlPotential(p - vec3(0.0, e, 0.0));
  vec3 y1 = curlPotential(p + vec3(0.0, e, 0.0));
  vec3 z0 = curlPotential(p - vec3(0.0, 0.0, e));
  vec3 z1 = curlPotential(p + vec3(0.0, 0.0, e));
  return vec3(
    (y1.z - y0.z) - (z1.y - z0.y),
    (z1.x - z0.x) - (x1.z - x0.z),
    (x1.y - x0.y) - (y1.x - y0.x)
  ) / (2.0 * e);
}

float hexDist(vec2 p) {
  p = abs(p);
  float c = dot(p, normalize(vec2(1.0, 1.73)));
  return max(c, p.x);
}

vec4 hexCoords(vec2 uv) {
  vec2 r = vec2(1.0, 1.73);
  vec2 h = r * 0.5;
  vec2 a = mod(uv, r) - h;
  vec2 b = mod(uv - h, r) - h;
  vec2 gv = dot(a, a) < dot(b, b) ? a : b;
  return vec4(gv, uv - gv);
}

const vec3 HOLO_CYAN    = vec3(0.20, 0.92, 1.00);
const vec3 HOLO_MAGENTA = vec3(1.00, 0.24, 0.66);
const vec3 HOLO_VIOLET  = vec3(0.55, 0.38, 1.00);
const vec3 HOLO_MINT    = vec3(0.28, 1.00, 0.72);

float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

vec3 tonemapACES(vec3 x) {
  const float a = 2.51, b = 0.03, c = 2.43, d = 0.59, e = 0.14;
  return clamp((x * (a * x + b)) / (x * (c * x + d) + e), 0.0, 1.0);
}

vec3 spectrum(float t) {
  t = fract(t);
  return 0.5 + 0.5 * cos(6.28318 * (t + vec3(0.0, 0.33, 0.67)));
}
"""

/** The room: hex floor, haze, emitter shaft, touch ripples, lidar sweep. */
const val STAGE_FRAG = """#version 300 es
$COMMON

in vec2 vUv;
out vec4 fragColor;

uniform vec2  uResolution;
uniform float uTime;
uniform vec2  uTilt;        // device orientation, radians, smoothed
uniform float uEnergy;      // 0..1 excitement
uniform float uBoot;        // 0..1 power-on
uniform float uScanPhase;
uniform vec4  uRipples[6];  // xz origin on the floor, birth time, strength
uniform float uSteps;

const float FLOOR_Y = -3.4;

vec3 floorShade(vec3 p, float dist) {
  vec2 uv = p.xz * 0.62;
  vec4 hc = hexCoords(uv);
  float cell = hexDist(hc.xy);
  float seam = smoothstep(0.455, 0.5, cell);
  float seamCore = smoothstep(0.489, 0.5, cell);

  float cellSeed = hash11(hc.z * 37.13 + hc.w * 91.7);
  float pulse = sin(uTime * 1.2 + cellSeed * 6.28318 + length(hc.zw) * 0.35);
  float litCell = smoothstep(0.90, 1.0, pulse) * (0.25 + 0.75 * uEnergy);

  float rings = 0.0;
  for (int i = 0; i < 6; i++) {
    vec4 r = uRipples[i];
    if (r.w <= 0.0) continue;
    float age = uTime - r.z;
    if (age < 0.0 || age > 2.6) continue;
    float d = length(p.xz - r.xy);
    float radius = age * 8.0;
    rings += exp(-abs(d - radius) * 1.7) * exp(-age * 1.5) * r.w;
  }

  vec3 tint = mix(HOLO_CYAN, HOLO_VIOLET, smoothstep(-14.0, 14.0, p.x) * 0.7);
  vec3 col = tint * (seam * 0.10 + seamCore * 0.44);
  col += HOLO_MINT * litCell * (1.0 - seam) * 0.18;
  col += mix(HOLO_CYAN, HOLO_MAGENTA, 0.5) * rings * 1.8;
  col += HOLO_VIOLET * exp(-abs(p.z - uScanPhase) * 1.4) * 0.5 * (0.4 + uEnergy);
  return col * exp(-dist * 0.05);
}

void main() {
  vec2 uv = vUv * 2.0 - 1.0;
  float aspect = uResolution.x / uResolution.y;

  // The camera is fixed in the room; tilting the phone swings it. This is
  // the phone's answer to the desktop's pointer parallax, and it is the one
  // thing that most makes the volume read as physically behind the glass.
  vec3 ro = vec3(uTilt.x * 3.4, 0.6 + uTilt.y * 2.6, 15.0);
  vec3 fwd = normalize(vec3(-uTilt.x * 0.16, -uTilt.y * 0.13, -1.0));
  vec3 right = normalize(cross(fwd, vec3(0.0, 1.0, 0.0)));
  vec3 up = cross(right, fwd);
  vec3 rd = normalize(fwd + right * uv.x * 0.45 * aspect + up * uv.y * 0.45);

  vec3 col = vec3(0.0);
  float horizon = smoothstep(-0.35, 0.65, rd.y);
  col += mix(vec3(0.010, 0.024, 0.040), vec3(0.004, 0.008, 0.020), horizon);
  col += HOLO_VIOLET * 0.035 * pow(1.0 - abs(rd.y), 6.0);

  vec3 sdir = rd * 130.0;
  float starMask = step(0.62, hash13(floor(sdir)));
  float stars = pow(max(0.0, gnoise(sdir)), 26.0) * 1400.0 * starMask;
  col += mix(vec3(0.55, 0.85, 1.0), HOLO_MAGENTA, step(0.88, hash13(floor(sdir) + 7.0)))
       * stars * (0.5 + 0.5 * sin(uTime * 2.6));

  float tFloor = 1e9;
  if (rd.y < -0.0001) {
    float t = (FLOOR_Y - ro.y) / rd.y;
    if (t > 0.0) tFloor = t;
  }
  if (tFloor < 300.0) col += floorShade(ro + rd * tFloor, tFloor);

  // Volumetric haze. Emission-absorption, so the integral stays bounded
  // however long the ray or however dense the medium.
  float far = min(tFloor, 70.0);
  int steps = int(clamp(uSteps, 5.0, 20.0));
  float stepLen = far / float(steps);
  float t = stepLen * hash13(vec3(gl_FragCoord.xy, uTime * 60.0));
  float transmittance = 1.0;
  vec3 fog = vec3(0.0);
  for (int i = 0; i < 20; i++) {
    if (i >= steps || transmittance < 0.03) break;
    vec3 p = ro + rd * t;
    vec3 q = p * 0.15 + vec3(uTime * 0.035, uTime * -0.02, uTime * 0.045);
    float density = smoothstep(-0.15, 0.55, fbm3(q)) * exp(-max(0.0, p.y - FLOOR_Y) * 0.24) * 0.06;
    if (density > 0.001) {
      float axial = length(p.xz);
      float shaft = exp(-axial * axial * 0.012) * smoothstep(-12.0, 8.0, p.y);
      vec3 lit = mix(HOLO_CYAN, HOLO_VIOLET, smoothstep(-4.0, 8.0, p.y)) * 0.32;
      lit += HOLO_MAGENTA * shaft * 0.5;
      float absorb = 1.0 - exp(-density * stepLen);
      fog += lit * absorb * transmittance;
      transmittance *= 1.0 - absorb;
    }
    t += stepLen;
  }
  col += fog;

  col *= smoothstep(0.0, 0.85, uBoot);
  col += HOLO_CYAN * exp(-length(uv) * 8.0) * (1.0 - smoothstep(0.0, 0.6, uBoot)) * 2.2;

  fragColor = vec4(col, 1.0);
}
"""

/**
 * Dust. Stateless by design: positions come from a closed-form function of
 * the vertex id and time, so there is no simulation state, no float render
 * targets and no ping-pong — none of which a mid-range phone should be
 * spending its budget on. Touch points still deform the field, because that
 * is the part the user can actually feel.
 */
const val PARTICLE_VERT = """#version 300 es
$COMMON

uniform float uTime;
uniform vec2  uResolution;
uniform vec2  uTilt;
uniform float uEnergy;
uniform float uBoot;
uniform vec4  uTouch;     // xy world position, z birth time, w strength
uniform float uCount;

out vec3 vColor;
out float vAlpha;

void main() {
  float id = float(gl_VertexID);
  float a = hash11(id) * 6.28318;
  float band = hash11(id + 91.7);
  float rad = 2.5 + band * 11.0;
  float drift = uTime * (0.05 + 0.06 * hash11(id + 3.1)) * (1.0 + uEnergy);

  vec3 pos = vec3(
    cos(a + drift) * rad,
    -3.4 + hash11(id + 17.0) * 15.0 + sin(drift * 2.1 + a) * 1.2,
    sin(a + drift) * rad * 0.65
  );
  pos += curlNoise(pos * 0.09 + uTime * 0.05) * 1.5;

  // A touch pushes a shell of dust outward from where the finger landed and
  // lets it fall back — the same impulse the ripple on the floor came from.
  float age = uTime - uTouch.z;
  if (uTouch.w > 0.0 && age > 0.0 && age < 1.6) {
    vec3 d = pos - vec3(uTouch.xy, 0.0);
    float dist = length(d) + 1e-3;
    float wave = exp(-abs(dist - age * 9.0) * 0.5) * exp(-age * 1.6);
    pos += (d / dist) * wave * uTouch.w * 2.4;
  }

  // Fixed camera matching the stage shader, tilt included.
  vec3 ro = vec3(uTilt.x * 3.4, 0.6 + uTilt.y * 2.6, 15.0);
  vec3 fwd = normalize(vec3(-uTilt.x * 0.16, -uTilt.y * 0.13, -1.0));
  vec3 right = normalize(cross(fwd, vec3(0.0, 1.0, 0.0)));
  vec3 up = cross(right, fwd);

  vec3 rel = pos - ro;
  float z = dot(rel, fwd);
  if (z < 0.3) { gl_Position = vec4(2.0, 2.0, 2.0, 1.0); vAlpha = 0.0; vColor = vec3(0.0); return; }
  float aspect = uResolution.x / uResolution.y;
  vec2 ndc = vec2(dot(rel, right) / (0.45 * aspect), dot(rel, up) / 0.45) / z;
  gl_Position = vec4(ndc, 0.0, 1.0);

  float sizeVar = 0.30 + 2.2 * pow(hash11(id * 0.37), 3.0);
  gl_PointSize = clamp(uResolution.y * 0.10 * sizeVar / z, 1.5, 60.0);

  float depthFade = clamp(1.0 - z / 26.0, 0.0, 1.0);
  vColor = mix(vec3(0.20, 0.85, 1.00), vec3(0.62, 0.42, 1.0), step(0.86, hash11(id * 0.017)));
  vColor = mix(vColor, vec3(1.00, 0.32, 0.72), uEnergy * 0.35);
  vAlpha = depthFade * (0.10 + 0.26 * hash11(id + 51.0)) * uBoot;
}
"""

const val PARTICLE_FRAG = """#version 300 es
precision mediump float;
in vec3 vColor;
in float vAlpha;
out vec4 fragColor;
void main() {
  vec2 d = gl_PointCoord * 2.0 - 1.0;
  float r2 = dot(d, d);
  if (r2 > 1.0) discard;
  float core = exp(-r2 * 6.5);
  float halo = exp(-r2 * 1.7) * 0.28;
  fragColor = vec4(vColor * (core * 1.7 + halo * 0.6), (core + halo) * vAlpha);
}
"""

const val BRIGHT_FRAG = """#version 300 es
precision mediump float;
in vec2 vUv;
out vec4 fragColor;
uniform sampler2D uScene;
uniform vec2 uTexel;
void main() {
  vec3 c = texture(uScene, vUv + vec2(-uTexel.x, -uTexel.y)).rgb;
  c += texture(uScene, vUv + vec2(uTexel.x, -uTexel.y)).rgb;
  c += texture(uScene, vUv + vec2(-uTexel.x, uTexel.y)).rgb;
  c += texture(uScene, vUv + vec2(uTexel.x, uTexel.y)).rgb;
  c *= 0.25;
  float br = max(c.r, max(c.g, c.b));
  fragColor = vec4(c * smoothstep(0.45, 0.85, br), 1.0);
}
"""

const val BLUR_FRAG = """#version 300 es
precision mediump float;
in vec2 vUv;
out vec4 fragColor;
uniform sampler2D uSource;
uniform vec2 uDirection;
void main() {
  vec3 sum = texture(uSource, vUv).rgb * 0.227027;
  vec2 o1 = uDirection * 1.3846153846;
  vec2 o2 = uDirection * 3.2307692308;
  sum += texture(uSource, vUv + o1).rgb * 0.3162162162;
  sum += texture(uSource, vUv - o1).rgb * 0.3162162162;
  sum += texture(uSource, vUv + o2).rgb * 0.0702702703;
  sum += texture(uSource, vUv - o2).rgb * 0.0702702703;
  fragColor = vec4(sum, 1.0);
}
"""

/** Optics: bloom, aberration, barrel, grille, grain, glitch, vignette. */
const val COMPOSITE_FRAG = """#version 300 es
$COMMON

in vec2 vUv;
out vec4 fragColor;

uniform sampler2D uScene;
uniform sampler2D uBloom;
uniform vec2  uResolution;
uniform float uTime;
uniform float uGlitch;
uniform float uEnergy;
uniform float uBoot;
uniform vec2  uTilt;

void main() {
  vec2 uv = vUv;

  if (uGlitch > 0.001) {
    float band = floor(uv.y * 30.0 + uTime * 2.0);
    float roll = hash11(band + floor(uTime * 18.0));
    float bandOn = step(0.74 - uGlitch * 0.4, roll);
    uv.x += (roll - 0.5) * uGlitch * 0.08 * bandOn;
  }

  vec2 c = uv - 0.5;
  float r2 = dot(c, c);
  uv = 0.5 + c * (1.0 + 0.030 * r2);

  float r = length(uv - 0.5);
  vec2 dir = r > 1e-5 ? (uv - 0.5) / r : vec2(0.0);
  // Aberration leans with the device: the "lens" is physically tilting.
  float ab = (0.0014 + r * r * 0.009) * (1.0 + uGlitch * 5.0) + abs(uTilt.x) * 0.0016;

  vec3 col;
  col.r = texture(uScene, uv + dir * ab).r;
  col.g = texture(uScene, uv).g;
  col.b = texture(uScene, uv - dir * ab).b;

  vec3 bloom = texture(uBloom, uv).rgb;
  bloom.r = texture(uBloom, uv + dir * ab * 2.4).r;
  bloom.b = texture(uBloom, uv - dir * ab * 2.4).b;
  col += bloom * 0.85;

  float scanline = 0.5 + 0.5 * sin(uv.y * uResolution.y * 1.4 - uTime * 3.0);
  col *= mix(1.0, 0.90 + 0.10 * scanline, 0.30);

  float sweep = fract(uv.y * 0.5 - uTime * 0.07);
  col += vec3(0.04, 0.12, 0.16) * exp(-sweep * 12.0) * (0.4 + uEnergy);

  col *= mix(0.38, 1.0, smoothstep(1.05, 0.3, r));
  col *= smoothstep(0.0, 0.35, uBoot);
  col = tonemapACES(col * 0.98);
  col += (hash13(vec3(gl_FragCoord.xy, uTime * 91.0)) - 0.5) * 0.030;

  if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) col *= 0.0;
  fragColor = vec4(col, 1.0);
}
"""
