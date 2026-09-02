import { compose, damp, lookAt, mat4, multiply, perspective, TAU, clamp, type Mat4, type Vec3 } from './math.js';

/**
 * The stage camera.
 *
 * It is never keyframed. Its resting pose is a slow Lissajous drift so the
 * projection is always subtly alive, and every user input (pointer
 * parallax, wheel dolly, drag orbit) feeds a critically-damped target the
 * drift rides on top of. That combination is what makes the scene feel
 * hand-held rather than animated.
 */
export class Camera {
  fov = (46 * Math.PI) / 180;
  aspect = 16 / 9;
  near = 0.1;
  far = 400;

  position: Vec3 = [0, 0, 16];
  target: Vec3 = [0, 0, 0];
  right: Vec3 = [1, 0, 0];
  upVector: Vec3 = [0, 1, 0];
  forward: Vec3 = [0, 0, -1];

  readonly view: Mat4 = mat4();
  readonly projection: Mat4 = mat4();
  readonly viewProjection: Mat4 = mat4();

  /** Orbit targets, driven by drag; the current values chase them. */
  private yaw = 0;
  private pitch = 0;
  private yawTarget = 0;
  private pitchTarget = 0;
  private distance = 16;
  private distanceTarget = 16;
  private panX = 0;
  private panXTarget = 0;
  private panY = 0;
  private panYTarget = 0;
  private drift = 1;

  /** Suppresses the idle drift while the user is actively driving. */
  private idleTimer = 0;

  setParallax(ndcX: number, ndcY: number) {
    this.yawTarget = ndcX * 0.085;
    this.pitchTarget = -ndcY * 0.055;
  }

  addOrbit(dx: number, dy: number) {
    this.yawTarget = clamp(this.yawTarget + dx * 0.004, -0.75, 0.75);
    this.pitchTarget = clamp(this.pitchTarget + dy * 0.003, -0.45, 0.45);
    this.idleTimer = 0;
  }

  setFocus(x: number, y: number, distance: number) {
    this.panXTarget = x;
    this.panYTarget = y;
    this.distanceTarget = distance;
  }

  /** 0 = fully settled and drifting, 1 = user is driving. */
  setDriftAmount(v: number) { this.drift = v; }

  update(dt: number, time: number) {
    this.idleTimer += dt;
    this.yaw = damp(this.yaw, this.yawTarget, 4.5, dt);
    this.pitch = damp(this.pitch, this.pitchTarget, 4.5, dt);
    this.distance = damp(this.distance, this.distanceTarget, 5, dt);
    this.panX = damp(this.panX, this.panXTarget, 5, dt);
    this.panY = damp(this.panY, this.panYTarget, 5, dt);

    // Idle drift: two incommensurable periods, so the loop never repeats
    // visibly within a session.
    const idle = Math.min(1, this.idleTimer / 2.2) * this.drift;
    const driftYaw = Math.sin(time * 0.13) * 0.05 + Math.sin(time * 0.071) * 0.03;
    const driftPitch = Math.cos(time * 0.097) * 0.03 + Math.sin(time * 0.043) * 0.015;
    const driftZ = Math.sin(time * 0.055) * 0.5;

    const yaw = this.yaw + driftYaw * idle;
    const pitch = this.pitch + driftPitch * idle;
    const dist = this.distance + driftZ * idle;

    const cp = Math.cos(pitch);
    this.position[0] = this.panX + Math.sin(yaw) * cp * dist;
    this.position[1] = this.panY + Math.sin(pitch) * dist;
    this.position[2] = Math.cos(yaw) * cp * dist;
    this.target[0] = this.panX * 0.6;
    this.target[1] = this.panY * 0.6;
    this.target[2] = 0;

    lookAt(this.view, this.position, this.target, [0, 1, 0]);
    perspective(this.projection, this.fov, this.aspect, this.near, this.far);
    multiply(this.viewProjection, this.projection, this.view);

    // The view matrix rows are the camera basis; reading them back beats
    // recomputing the cross products.
    this.right[0] = this.view[0]; this.right[1] = this.view[4]; this.right[2] = this.view[8];
    this.upVector[0] = this.view[1]; this.upVector[1] = this.view[5]; this.upVector[2] = this.view[9];
    this.forward[0] = -this.view[2]; this.forward[1] = -this.view[6]; this.forward[2] = -this.view[10];
  }

  /**
   * Half-extents of the view frustum on the z = 0 plane, in world units —
   * the conversion factor between screen layout and stage layout.
   */
  visibleHalfSize(z = 0): { halfWidth: number; halfHeight: number } {
    const dist = Math.abs(this.position[2] - z);
    const halfHeight = Math.tan(this.fov / 2) * dist;
    return { halfHeight, halfWidth: halfHeight * this.aspect };
  }
}

export { compose, TAU };
