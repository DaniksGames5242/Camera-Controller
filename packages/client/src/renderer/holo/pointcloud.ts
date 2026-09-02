/**
 * Turns glyphs and shapes into point clouds the particle solver can
 * assemble into.
 *
 * Rasterising text to a scratch 2D canvas and harvesting its lit pixels is
 * the cheapest way to get an arbitrary silhouette — any font, any string,
 * any language — into the simulation without shipping geometry.
 */

export interface TextCloudOptions {
  /** World-space width the cloud is fitted into. */
  width: number;
  /** Approximate number of points to emit. */
  count?: number;
  font?: string;
  /** Depth jitter, so the assembled glyphs have volume rather than being flat. */
  depth?: number;
  z?: number;
  y?: number;
}

export function textPointCloud(text: string, opts: TextCloudOptions): Float32Array {
  const count = opts.count ?? 6000;
  const font = opts.font ?? '700 128px "Segoe UI", system-ui, sans-serif';
  const depth = opts.depth ?? 0.35;

  const measureCanvas = document.createElement('canvas');
  const mctx = measureCanvas.getContext('2d');
  if (!mctx) return new Float32Array(0);
  mctx.font = font;
  const metrics = mctx.measureText(text);
  const textWidth = Math.max(1, Math.ceil(metrics.width));
  const ascent = metrics.actualBoundingBoxAscent || 96;
  const descent = metrics.actualBoundingBoxDescent || 32;
  const textHeight = Math.max(1, Math.ceil(ascent + descent));

  const pad = 8;
  const canvas = document.createElement('canvas');
  canvas.width = textWidth + pad * 2;
  canvas.height = textHeight + pad * 2;
  const ctx = canvas.getContext('2d', { willReadFrequently: true });
  if (!ctx) return new Float32Array(0);
  ctx.font = font;
  ctx.fillStyle = '#fff';
  ctx.textBaseline = 'alphabetic';
  ctx.fillText(text, pad, pad + ascent);

  const image = ctx.getImageData(0, 0, canvas.width, canvas.height).data;

  // Collect lit pixels first, then sample from them: an even stride over
  // the raster biases toward whatever the scan order happens to hit, and
  // leaves gaps in thin strokes.
  const lit: number[] = [];
  for (let y = 0; y < canvas.height; y++) {
    for (let x = 0; x < canvas.width; x++) {
      if (image[(y * canvas.width + x) * 4 + 3] > 96) lit.push(x, y);
    }
  }
  if (lit.length === 0) return new Float32Array(0);

  const litCount = lit.length / 2;
  const scale = opts.width / canvas.width;
  const originX = -opts.width / 2;
  const originY = (canvas.height * scale) / 2 + (opts.y ?? 0);
  const z = opts.z ?? 0;

  const points = new Float32Array(count * 3);
  for (let i = 0; i < count; i++) {
    const j = (Math.random() * litCount) | 0;
    // Sub-pixel jitter fills the gaps between raster samples so the glyph
    // edges stay smooth however many particles land on them.
    const px = lit[j * 2] + Math.random();
    const py = lit[j * 2 + 1] + Math.random();
    points[i * 3] = originX + px * scale;
    points[i * 3 + 1] = originY - py * scale;
    points[i * 3 + 2] = z + (Math.random() - 0.5) * depth;
  }
  return points;
}

/** A hollow ring of points — used as the idle "standby" attractor. */
export function ringPointCloud(count: number, radius: number, thickness: number, y = 0): Float32Array {
  const points = new Float32Array(count * 3);
  for (let i = 0; i < count; i++) {
    const a = Math.random() * Math.PI * 2;
    const r = radius + (Math.random() - 0.5) * thickness;
    points[i * 3] = Math.cos(a) * r;
    points[i * 3 + 1] = y + (Math.random() - 0.5) * thickness;
    points[i * 3 + 2] = Math.sin(a) * r;
  }
  return points;
}
