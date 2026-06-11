import { defineConfig } from 'vite'

// base = Repo-Name, damit Asset-Pfade unter gabriel-graf.github.io/ComicGuide/ stimmen.
// Das Kotlin/JS-Bundle ist ein CommonJS/UMD-Modul in src/ — der commonjs-Plugin
// von Rollup greift dort standardmäßig nicht (nur node_modules), daher explizit
// einbinden, damit der Default-Import (`import cc from '…/comic-cutter.js'`) auflöst.
export default defineConfig({
  base: '/ComicGuide/',
  build: {
    commonjsOptions: {
      include: [/comic-cutter\.js$/, /node_modules/],
    },
  },
})
