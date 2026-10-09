import { realpathSync } from 'node:fs';
import { resolve } from 'node:path';
import { defineConfig } from 'vite';

// Gradle runs this through an ASCII junction while Node resolves real (non-ASCII) paths;
// anchoring every path at the real directory keeps Vite's root and module ids consistent.
const here = realpathSync.native(process.cwd());

// Built into virga-panel/build/panel-web/panel, then packed into the JAR under panel/.
export default defineConfig({
  root: resolve(here, 'src'),
  publicDir: resolve(here, 'public'),
  base: '/',
  build: { outDir: resolve(here, '../build/panel-web/panel'), emptyOutDir: true, assetsDir: 'assets', sourcemap: false }
});
