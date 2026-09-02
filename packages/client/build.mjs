import esbuild from 'esbuild';
import { cpSync, mkdirSync, writeFileSync } from 'node:fs';

mkdirSync('dist/renderer', { recursive: true });

await esbuild.build({
  entryPoints: ['src/main.ts', 'src/preload.ts'],
  outdir: 'dist',
  bundle: true,
  platform: 'node',
  target: 'node20',
  format: 'cjs',
  external: ['electron'],
});

await esbuild.build({
  entryPoints: ['src/renderer/app.ts'],
  outdir: 'dist/renderer',
  bundle: true,
  platform: 'browser',
  target: 'chrome124',
  format: 'iife',
});

cpSync('src/renderer/index.html', 'dist/renderer/index.html');
cpSync('src/renderer/style.css', 'dist/renderer/style.css');

// Self-contained manifest for electron-builder to package *instead of* this
// workspace package's own package.json — everything is already bundled by
// esbuild above, so this app has zero runtime dependencies. Deliberately no
// "dependencies" field: electron-builder's own "installing production
// dependencies" step runs `npm install` scoped to whatever directory holds
// this manifest, and since dist/ isn't itself an npm-workspace member (no
// "workspaces" field up its own chain), that install can't reach back into
// — and prune dev dependencies out of — the monorepo root.
writeFileSync(
  'dist/package.json',
  JSON.stringify({ name: 'mycamerascontroller-client', version: '0.1.0', main: 'main.js' }, null, 2)
);

console.log('client build complete');
