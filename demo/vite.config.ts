import { defineConfig } from 'vite'

// base = repo name, so that asset paths under gabriel-graf.github.io/ComicCutter/ are correct.
// The Kotlin/JS bundle is a CommonJS/UMD module in src/ — Rollup's commonjs plugin
// doesn't apply there by default (only node_modules), so include it explicitly
// so that the default import (`import cc from '…/comic-cutter.js'`) resolves.
export default defineConfig({
  base: '/ComicCutter/',
  build: {
    commonjsOptions: {
      include: [/comic-cutter\.js$/, /node_modules/],
    },
  },
})
