/**
 * Thin WebGL2 layer: program compilation with useful error reporting,
 * cached uniform lookups, render targets, and the fullscreen triangle every
 * post-processing pass draws.
 *
 * Nothing here knows anything about the hologram — it is the substrate the
 * passes in this folder are written against.
 */

export interface GLCaps {
  /** Renderable float colour attachments — required by the particle solver. */
  floatRenderTargets: boolean;
  /** Linear filtering of float textures — nice-to-have for smooth bloom. */
  floatLinear: boolean;
  maxTextureSize: number;
}

export class Program {
  readonly gl: WebGL2RenderingContext;
  readonly handle: WebGLProgram;
  private readonly uniforms = new Map<string, WebGLUniformLocation | null>();

  constructor(gl: WebGL2RenderingContext, vertexSource: string, fragmentSource: string, label = 'program') {
    this.gl = gl;
    const vs = compileShader(gl, gl.VERTEX_SHADER, vertexSource, `${label}.vert`);
    const fs = compileShader(gl, gl.FRAGMENT_SHADER, fragmentSource, `${label}.frag`);
    const handle = gl.createProgram();
    if (!handle) throw new Error(`${label}: createProgram failed`);
    gl.attachShader(handle, vs);
    gl.attachShader(handle, fs);
    gl.linkProgram(handle);
    if (!gl.getProgramParameter(handle, gl.LINK_STATUS)) {
      const log = gl.getProgramInfoLog(handle);
      gl.deleteProgram(handle);
      throw new Error(`${label}: link failed\n${log}`);
    }
    // The shader objects are referenced by the linked program; dropping our
    // own handles here keeps the driver's object table small.
    gl.deleteShader(vs);
    gl.deleteShader(fs);
    this.handle = handle;
  }

  use(): this {
    this.gl.useProgram(this.handle);
    return this;
  }

  loc(name: string): WebGLUniformLocation | null {
    let l = this.uniforms.get(name);
    if (l === undefined) {
      l = this.gl.getUniformLocation(this.handle, name);
      this.uniforms.set(name, l);
    }
    return l;
  }

  f(name: string, v: number): this { this.gl.uniform1f(this.loc(name), v); return this; }
  i(name: string, v: number): this { this.gl.uniform1i(this.loc(name), v); return this; }
  v2(name: string, x: number, y: number): this { this.gl.uniform2f(this.loc(name), x, y); return this; }
  v3(name: string, x: number, y: number, z: number): this { this.gl.uniform3f(this.loc(name), x, y, z); return this; }
  v4(name: string, x: number, y: number, z: number, w: number): this { this.gl.uniform4f(this.loc(name), x, y, z, w); return this; }
  m4(name: string, m: Float32Array): this { this.gl.uniformMatrix4fv(this.loc(name), false, m); return this; }

  /** Binds `texture` to `unit` and points the sampler uniform at it. */
  tex(name: string, unit: number, texture: WebGLTexture | null, target = this.gl.TEXTURE_2D): this {
    const gl = this.gl;
    gl.activeTexture(gl.TEXTURE0 + unit);
    gl.bindTexture(target, texture);
    gl.uniform1i(this.loc(name), unit);
    return this;
  }

  dispose() { this.gl.deleteProgram(this.handle); }
}

function compileShader(gl: WebGL2RenderingContext, type: number, source: string, label: string): WebGLShader {
  const shader = gl.createShader(type);
  if (!shader) throw new Error(`${label}: createShader failed`);
  gl.shaderSource(shader, source);
  gl.compileShader(shader);
  if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
    const log = gl.getShaderInfoLog(shader) ?? '';
    // Line numbers in the driver log are relative to the concatenated
    // source, which is assembled from chunks — printing the numbered source
    // alongside is the only way to find the offending line quickly.
    const numbered = source.split('\n').map((l, i) => `${String(i + 1).padStart(4)} | ${l}`).join('\n');
    gl.deleteShader(shader);
    throw new Error(`${label}: compile failed\n${log}\n${numbered}`);
  }
  return shader;
}

export interface TargetOptions {
  float?: boolean;
  linear?: boolean;
  depth?: boolean;
  wrap?: number;
}

/** A single-colour-attachment framebuffer that can be resized in place. */
export class RenderTarget {
  readonly gl: WebGL2RenderingContext;
  framebuffer: WebGLFramebuffer;
  texture: WebGLTexture;
  depthBuffer: WebGLRenderbuffer | null = null;
  width = 0;
  height = 0;
  private readonly opts: Required<TargetOptions>;

  constructor(gl: WebGL2RenderingContext, width: number, height: number, opts: TargetOptions = {}) {
    this.gl = gl;
    this.opts = {
      float: opts.float ?? false,
      linear: opts.linear ?? true,
      depth: opts.depth ?? false,
      wrap: opts.wrap ?? gl.CLAMP_TO_EDGE,
    };
    this.framebuffer = gl.createFramebuffer()!;
    this.texture = gl.createTexture()!;
    if (this.opts.depth) this.depthBuffer = gl.createRenderbuffer();
    this.resize(width, height);
  }

  resize(width: number, height: number) {
    const gl = this.gl;
    const w = Math.max(1, Math.floor(width));
    const h = Math.max(1, Math.floor(height));
    if (w === this.width && h === this.height) return;
    this.width = w;
    this.height = h;

    const internal = this.opts.float ? gl.RGBA16F : gl.RGBA8;
    const type = this.opts.float ? gl.HALF_FLOAT : gl.UNSIGNED_BYTE;
    const filter = this.opts.linear ? gl.LINEAR : gl.NEAREST;

    gl.bindTexture(gl.TEXTURE_2D, this.texture);
    gl.texImage2D(gl.TEXTURE_2D, 0, internal, w, h, 0, gl.RGBA, type, null);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, filter);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, filter);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, this.opts.wrap);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, this.opts.wrap);

    gl.bindFramebuffer(gl.FRAMEBUFFER, this.framebuffer);
    gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, this.texture, 0);
    if (this.depthBuffer) {
      gl.bindRenderbuffer(gl.RENDERBUFFER, this.depthBuffer);
      gl.renderbufferStorage(gl.RENDERBUFFER, gl.DEPTH_COMPONENT16, w, h);
      gl.framebufferRenderbuffer(gl.FRAMEBUFFER, gl.DEPTH_ATTACHMENT, gl.RENDERBUFFER, this.depthBuffer);
    }
    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
  }

  bind() {
    const gl = this.gl;
    gl.bindFramebuffer(gl.FRAMEBUFFER, this.framebuffer);
    gl.viewport(0, 0, this.width, this.height);
  }

  dispose() {
    const gl = this.gl;
    gl.deleteFramebuffer(this.framebuffer);
    gl.deleteTexture(this.texture);
    if (this.depthBuffer) gl.deleteRenderbuffer(this.depthBuffer);
  }
}

/**
 * Data texture used both as simulation state (ping-pong solver) and as a
 * lookup table (particle spawn targets). RGBA32F when available so a full
 * world-space position survives a round trip without quantisation.
 */
export function createDataTexture(
  gl: WebGL2RenderingContext,
  width: number,
  height: number,
  data: Float32Array | null,
  float: boolean
): WebGLTexture {
  const tex = gl.createTexture()!;
  gl.bindTexture(gl.TEXTURE_2D, tex);
  if (float) {
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA32F, width, height, 0, gl.RGBA, gl.FLOAT, data);
  } else {
    const bytes = data ? new Uint8Array(data.length) : null;
    if (bytes && data) for (let i = 0; i < data.length; i++) bytes[i] = Math.max(0, Math.min(255, data[i] * 255));
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, width, height, 0, gl.RGBA, gl.UNSIGNED_BYTE, bytes);
  }
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
  return tex;
}

/**
 * One oversized triangle instead of a quad: no diagonal seam, one fewer
 * vertex, and the GPU rasterises each pixel exactly once.
 */
export class FullscreenTriangle {
  private readonly gl: WebGL2RenderingContext;
  private readonly vao: WebGLVertexArrayObject;

  constructor(gl: WebGL2RenderingContext) {
    this.gl = gl;
    this.vao = gl.createVertexArray()!;
    const buffer = gl.createBuffer()!;
    gl.bindVertexArray(this.vao);
    gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW);
    gl.enableVertexAttribArray(0);
    gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
    gl.bindVertexArray(null);
  }

  draw() {
    const gl = this.gl;
    gl.bindVertexArray(this.vao);
    gl.drawArrays(gl.TRIANGLES, 0, 3);
  }
}

export const FULLSCREEN_VERT = /* glsl */ `#version 300 es
layout(location = 0) in vec2 aPos;
out vec2 vUv;
void main() {
  vUv = aPos * 0.5 + 0.5;
  gl_Position = vec4(aPos, 0.0, 1.0);
}
`;

export function detectCaps(gl: WebGL2RenderingContext): GLCaps {
  return {
    floatRenderTargets: !!gl.getExtension('EXT_color_buffer_float'),
    floatLinear: !!gl.getExtension('OES_texture_float_linear'),
    maxTextureSize: gl.getParameter(gl.MAX_TEXTURE_SIZE) as number,
  };
}
