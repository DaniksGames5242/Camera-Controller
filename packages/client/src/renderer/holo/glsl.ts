/**
 * Shared GLSL chunks.
 *
 * Every procedural effect in the app — the volumetric backdrop, the flow
 * field the particles swim in, the dissolve masks, the interference
 * fringes — is built from this one noise basis, which is what makes the
 * whole interface look like a single coherent projection rather than a pile
 * of separate effects.
 */

/** Hashes, gradient noise, fBm, and the curl field derived from it. */
export const NOISE = /* glsl */ `
float hash11(float p) {
  p = fract(p * 0.1031);
  p *= p + 33.33;
  p *= p + p;
  return fract(p);
}

vec2 hash22(vec2 p) {
  vec3 p3 = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973));
  p3 += dot(p3, p3.yzx + 33.33);
  return fract((p3.xx + p3.yz) * p3.zy);
}

vec3 hash33(vec3 p) {
  p = vec3(dot(p, vec3(127.1, 311.7, 74.7)),
           dot(p, vec3(269.5, 183.3, 246.1)),
           dot(p, vec3(113.5, 271.9, 124.6)));
  return fract(sin(p) * 43758.5453123) * 2.0 - 1.0;
}

float hash13(vec3 p) {
  p = fract(p * 0.1031);
  p += dot(p, p.zyx + 31.32);
  return fract((p.x + p.y) * p.z);
}

// Gradient (Perlin-style) noise. Quintic interpolant keeps second
// derivatives continuous, which matters because the curl field below
// differentiates it.
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

float fbm(vec3 p, int octaves) {
  float sum = 0.0;
  float amp = 0.5;
  // Irrational rotation between octaves so the lattice never re-aligns and
  // produces visible axis-aligned banding.
  mat3 rot = mat3(0.00, 0.80, 0.60, -0.80, 0.36, -0.48, -0.60, -0.48, 0.64);
  for (int i = 0; i < 8; i++) {
    if (i >= octaves) break;
    sum += amp * gnoise(p);
    p = rot * p * 2.02;
    amp *= 0.5;
  }
  return sum;
}

float fbm3(vec3 p) { return fbm(p, 3); }
float fbm5(vec3 p) { return fbm(p, 5); }

// Divergence-free velocity field: particles advected by it swirl and never
// pile up into clumps the way a raw noise field makes them. Built as the
// curl of a 3-component vector potential, sampled with central differences.
vec3 curlPotential(vec3 p) {
  return vec3(
    gnoise(p),
    gnoise(p + vec3(31.416, 17.713, 5.219)),
    gnoise(p + vec3(-19.137, 7.331, 42.881))
  );
}

vec3 curlNoise(vec3 p) {
  const float e = 0.28;
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
`;

/** Signed-distance helpers, hex tiling, and screen-space utilities. */
export const SDF = /* glsl */ `
float sdBox(vec2 p, vec2 b) {
  vec2 d = abs(p) - b;
  return length(max(d, 0.0)) + min(max(d.x, d.y), 0.0);
}

float sdRoundBox(vec2 p, vec2 b, float r) {
  return sdBox(p, b - r) - r;
}

float sdSegment(vec2 p, vec2 a, vec2 b) {
  vec2 pa = p - a, ba = b - a;
  float h = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
  return length(pa - ba * h);
}

// Corner brackets: the classic HUD frame, as a distance field so it stays
// razor sharp at any resolution and animates by moving a single number.
// The arm value is how far each bracket reaches along its edge, as a fraction of
// the half-extent, so 1.0 closes the frame into a full rectangle.
float sdBrackets(vec2 p, vec2 half_, float arm, float thickness) {
  vec2 q = abs(p);
  vec2 reach = half_ * clamp(arm, 0.0, 1.0);
  float horizontal = sdSegment(q, vec2(half_.x - reach.x, half_.y), vec2(half_.x, half_.y));
  float vertical = sdSegment(q, vec2(half_.x, half_.y - reach.y), vec2(half_.x, half_.y));
  return min(horizontal, vertical) - thickness;
}

float hexDist(vec2 p) {
  p = abs(p);
  float c = dot(p, normalize(vec2(1.0, 1.73)));
  return max(c, p.x);
}

// Returns .xy = local coords inside the cell, .zw = cell id.
vec4 hexCoords(vec2 uv) {
  vec2 r = vec2(1.0, 1.73);
  vec2 h = r * 0.5;
  vec2 a = mod(uv, r) - h;
  vec2 b = mod(uv - h, r) - h;
  vec2 gv = dot(a, a) < dot(b, b) ? a : b;
  vec2 id = uv - gv;
  return vec4(gv, id);
}

/** Anti-aliased fill of a distance field, using screen-space derivatives. */
float aaFill(float d) {
  float w = fwidth(d) * 0.9 + 1e-6;
  return 1.0 - smoothstep(-w, w, d);
}

/** Anti-aliased stroke of width w centred on the zero level set. */
float aaStroke(float d, float w) {
  float e = fwidth(d) * 0.9 + 1e-6;
  return 1.0 - smoothstep(w - e, w + e, abs(d));
}
`;

/** Colour-space helpers, tone mapping and the shared holographic palette. */
export const COLOR = /* glsl */ `
const vec3 HOLO_CYAN    = vec3(0.20, 0.92, 1.00);
const vec3 HOLO_MAGENTA = vec3(1.00, 0.24, 0.66);
const vec3 HOLO_VIOLET  = vec3(0.55, 0.38, 1.00);
const vec3 HOLO_MINT    = vec3(0.28, 1.00, 0.72);
const vec3 HOLO_AMBER   = vec3(1.00, 0.72, 0.28);

vec3 hueShift(vec3 c, float a) {
  const vec3 k = vec3(0.57735);
  float cosA = cos(a);
  return c * cosA + cross(k, c) * sin(a) + k * dot(k, c) * (1.0 - cosA);
}

float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

// Narkowicz's ACES fit: keeps the bright cores of the emitters from
// clipping to flat white the way a naive clamp does.
vec3 tonemapACES(vec3 x) {
  const float a = 2.51, b = 0.03, c = 2.43, d = 0.59, e = 0.14;
  return clamp((x * (a * x + b)) / (x * (c * x + d) + e), 0.0, 1.0);
}

// Wavelength-ish ramp used for the interference fringes on every surface —
// the single strongest cue that a surface is "a hologram" and not "a
// translucent panel".
vec3 spectrum(float t) {
  t = fract(t);
  return 0.5 + 0.5 * cos(6.28318 * (t + vec3(0.0, 0.33, 0.67)));
}
`;
