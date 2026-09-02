import type { Camera } from './camera.js';
import type { Panel } from './panels.js';

/**
 * Puts real DOM on a 3D slab.
 *
 * The chrome for each video feed (its label, its buttons) has to sit
 * *on* the holographic plate, not float in a flat overlay — otherwise the
 * illusion collapses the moment the camera drifts. Rather than mirroring
 * the GL camera in CSS (which desynchronises the instant either side
 * changes), the four corners of the slab are projected to screen space and
 * a homography is fitted through them. The result is exact by construction,
 * and the element stays a normal, hit-testable, focusable DOM node.
 */

const corner = new Float32Array(4);
const projected = new Float32Array(8);

export interface ProjectedQuad {
  /** CSS matrix3d string mapping the element's own box onto the slab. */
  matrix: string;
  /** False when the slab is behind the camera or degenerate. */
  visible: boolean;
  /** Screen-space centre, for tooltips and focus rings. */
  centerX: number;
  centerY: number;
  /** Approximate on-screen area in px², used to pick a level of detail. */
  area: number;
}

export function projectPanel(
  panel: Panel,
  camera: Camera,
  canvasWidth: number,
  canvasHeight: number,
  elementWidth: number,
  elementHeight: number
): ProjectedQuad {
  const m = panel.model;
  const vp = camera.viewProjection;

  // Corners in the slab's local space, in DOM order: top-left, top-right,
  // bottom-right, bottom-left. The slab's +y is up, the DOM's is down.
  const locals = [
    [-0.5, 0.5],
    [0.5, 0.5],
    [0.5, -0.5],
    [-0.5, -0.5],
  ];

  let visible = true;
  let cx = 0;
  let cy = 0;

  for (let i = 0; i < 4; i++) {
    const lx = locals[i][0];
    const ly = locals[i][1];
    // model → world
    const wx = m[0] * lx + m[4] * ly + m[12];
    const wy = m[1] * lx + m[5] * ly + m[13];
    const wz = m[2] * lx + m[6] * ly + m[14];
    // world → clip
    corner[0] = vp[0] * wx + vp[4] * wy + vp[8] * wz + vp[12];
    corner[1] = vp[1] * wx + vp[5] * wy + vp[9] * wz + vp[13];
    corner[2] = vp[2] * wx + vp[6] * wy + vp[10] * wz + vp[14];
    corner[3] = vp[3] * wx + vp[7] * wy + vp[11] * wz + vp[15];
    if (corner[3] <= 0.001) visible = false;
    const invW = 1 / (corner[3] || 1e-6);
    const ndcX = corner[0] * invW;
    const ndcY = corner[1] * invW;
    const sx = (ndcX * 0.5 + 0.5) * canvasWidth;
    const sy = (1 - (ndcY * 0.5 + 0.5)) * canvasHeight;
    projected[i * 2] = sx;
    projected[i * 2 + 1] = sy;
    cx += sx * 0.25;
    cy += sy * 0.25;
  }

  const h = fitUnitSquareHomography(projected);
  if (!h) return { matrix: '', visible: false, centerX: cx, centerY: cy, area: 0 };

  // Pre-scale so the element's own pixel box maps onto the unit square.
  const sx = 1 / Math.max(1, elementWidth);
  const sy = 1 / Math.max(1, elementHeight);
  const a = h[0] * sx, b = h[1] * sy, c = h[2];
  const d = h[3] * sx, e = h[4] * sy, f = h[5];
  const g = h[6] * sx, i2 = h[7] * sy;

  const matrix =
    `matrix3d(${a},${d},0,${g},${b},${e},0,${i2},0,0,1,0,${c},${f},0,1)`;

  const area = Math.abs(
    (projected[2] - projected[0]) * (projected[5] - projected[1]) -
    (projected[4] - projected[0]) * (projected[3] - projected[1])
  );

  return { matrix, visible, centerX: cx, centerY: cy, area };
}

/**
 * Closed-form homography taking the unit square to an arbitrary convex
 * quad. Cheaper and better conditioned than a general DLT solve, and this
 * is the only case that ever comes up here.
 *
 * `q` holds x0,y0,x1,y1,x2,y2,x3,y3 for the corners at (0,0), (1,0), (1,1)
 * and (0,1) respectively. Returns [a,b,c, d,e,f, g,h] with the bottom-right
 * element fixed at 1.
 */
function fitUnitSquareHomography(q: Float32Array): number[] | null {
  const x0 = q[0], y0 = q[1];
  const x1 = q[2], y1 = q[3];
  const x2 = q[4], y2 = q[5];
  const x3 = q[6], y3 = q[7];

  const sx = x0 - x1 + x2 - x3;
  const sy = y0 - y1 + y2 - y3;

  if (Math.abs(sx) < 1e-8 && Math.abs(sy) < 1e-8) {
    // Parallelogram: the projective terms vanish and this reduces to affine.
    return [x1 - x0, x3 - x0, x0, y1 - y0, y3 - y0, y0, 0, 0];
  }

  const dx1 = x1 - x2, dy1 = y1 - y2;
  const dx2 = x3 - x2, dy2 = y3 - y2;
  const den = dx1 * dy2 - dx2 * dy1;
  if (Math.abs(den) < 1e-8) return null;

  const g = (sx * dy2 - dx2 * sy) / den;
  const h = (dx1 * sy - sx * dy1) / den;

  return [
    x1 - x0 + g * x1, x3 - x0 + h * x3, x0,
    y1 - y0 + g * y1, y3 - y0 + h * y3, y0,
    g, h,
  ];
}

/** Screen pixel position → a point on the z = `depth` plane in world space. */
export function screenToWorld(
  camera: Camera,
  ndcX: number,
  ndcY: number,
  depth: number
): [number, number, number] {
  const tan = Math.tan(camera.fov / 2);
  const dirX = ndcX * tan * camera.aspect;
  const dirY = ndcY * tan;
  const fx = camera.forward[0] + camera.right[0] * dirX + camera.upVector[0] * dirY;
  const fy = camera.forward[1] + camera.right[1] * dirX + camera.upVector[1] * dirY;
  const fz = camera.forward[2] + camera.right[2] * dirX + camera.upVector[2] * dirY;
  const len = Math.hypot(fx, fy, fz) || 1;
  const nx = fx / len, ny = fy / len, nz = fz / len;

  // Intersect with the plane z = depth.
  const t = Math.abs(nz) < 1e-5 ? 20 : (depth - camera.position[2]) / nz;
  const tt = t > 0 ? t : 20;
  return [
    camera.position[0] + nx * tt,
    camera.position[1] + ny * tt,
    camera.position[2] + nz * tt,
  ];
}
