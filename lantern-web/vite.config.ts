// Builds the frontend as one self-contained HTML file: all JavaScript and CSS inlined, nothing
// loaded from the network. The file is the template the document model is written into (see
// scripts/render.mjs). It lands in target/classes so the module's jar carries it.
import { defineConfig } from 'vitest/config';
import { viteSingleFile } from 'vite-plugin-singlefile';

export default defineConfig({
  plugins: [viteSingleFile()],
  build: { outDir: 'target/classes/io/lantern/web', emptyOutDir: true },
  test: { include: ['test/**/*.test.{mjs,ts,tsx}'] },
});
