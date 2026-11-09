// Bundles src/runtime.js for QuickJS and copies src/host.js next to it. Output goes to the sources module's
// (gitignored) resources folder. Run with: npm ci && npm run build
import { build } from 'esbuild';
import { copyFileSync, mkdirSync, statSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const outDir = resolve(here, '../src/main/resources/js');
mkdirSync(outDir, { recursive: true });

await build({
  entryPoints: [resolve(here, 'src/runtime.js')],
  outfile: resolve(outDir, 'runtime.js'),
  bundle: true,
  format: 'iife',
  target: 'es2020',
  platform: 'neutral',
  minify: false,
  mainFields: ['module', 'main'],
  conditions: ['import', 'default'],
  logLevel: 'info',
});
copyFileSync(resolve(here, 'src/host.js'), resolve(outDir, 'host.js'));

for (const f of ['runtime.js', 'host.js']) {
  console.log(`${f}: ${statSync(resolve(outDir, f)).size} bytes`);
}
