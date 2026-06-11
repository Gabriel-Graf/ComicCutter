# ComicGuide Browser-Demo Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eine statische GitHub-Pages-Demo, die den reinen-Kotlin Panel-Detektor via Kotlin/JS im Browser ausführt — Beispiel-Comics + eigener Upload, Panels als Overlay mit Lesereihenfolge.

**Architecture:** Die Lib wird auf Kotlin-Multiplatform umgestellt (`commonMain` = Detektor, Targets `jvm()` für Komga + `js(IR)` für die Demo). Eine dünne `@JsExport`-Fassade liefert normalisierte Panels an eine statische Vite+TS-App in `demo/`. GitHub Actions baut das JS-Bundle + die Demo und deployed nach Pages.

**Tech Stack:** Kotlin 2.0.21 Multiplatform (jvm + js IR, ES-Module), Gradle 8.10.2, kotlin.test, Vite + TypeScript (vanilla, kein Framework), GitHub Actions Pages.

**Repo:** `/home/gabriel/Documents/Projekte/GuidedComic` (Gabriel-Graf/ComicGuide)

---

## Dateistruktur (Ziel)

```
ComicGuide/
├── build.gradle.kts                 # MODIFY → kotlin("multiplatform")
├── settings.gradle.kts              # unverändert (rootProject.name="comic-cutter")
├── src/
│   ├── commonMain/kotlin/com/panela/comiccutter/…   # MOVE aus src/main
│   ├── commonTest/kotlin/com/panela/comiccutter/…   # MOVE aus src/test (kotlin.test)
│   └── jsMain/kotlin/com/panela/comiccutter/js/DetectorJs.kt   # CREATE
│   └── jsTest/kotlin/com/panela/comiccutter/js/DetectorJsTest.kt # CREATE
├── demo/
│   ├── package.json, vite.config.ts, tsconfig.json, index.html  # CREATE
│   ├── src/main.ts            # CREATE  UI + Datenfluss
│   ├── src/detect.ts          # CREATE  RGBA→ARGB + Aufruf der Fassade
│   ├── src/render.ts          # CREATE  Overlay-Rendering
│   ├── src/kotlin/            # CREATE (gitignored) Kotlin/JS-Bundle landet hier
│   ├── comics/                # CREATE  ~10 PD-Seiten (JPG)
│   ├── comics.json            # CREATE
│   └── PROVENANCE.md          # CREATE
├── .github/workflows/pages.yml   # CREATE
└── README.md                     # MODIFY (Demo-Link, 0.2.0-Koordinate)
```

---

## Task 1: Lib auf Kotlin-Multiplatform umstellen (jvm + js), Tests migrieren

**Files:**
- Move: `src/main/kotlin/**` → `src/commonMain/kotlin/**`
- Move: `src/test/kotlin/**` → `src/commonTest/kotlin/**`
- Modify: alle `src/commonTest/**/*.kt` mit `org.junit`-Import
- Modify: `build.gradle.kts`

- [ ] **Step 1: Quellen in die KMP-Sourcesets verschieben**

```bash
cd /home/gabriel/Documents/Projekte/GuidedComic
mkdir -p src/commonMain/kotlin src/commonTest/kotlin
git mv src/main/kotlin/com src/commonMain/kotlin/com
git mv src/test/kotlin/com src/commonTest/kotlin/com
rmdir src/main/kotlin src/main src/test/kotlin src/test 2>/dev/null || true
```

- [ ] **Step 2: JUnit-Annotationen auf kotlin.test umstellen**

`org.junit.jupiter.api.Test` ist JVM-only und bricht `commonTest`. Ersetze den Import in allen betroffenen Dateien:

```bash
cd /home/gabriel/Documents/Projekte/GuidedComic
grep -rl 'org.junit.jupiter.api.Test' src/commonTest | xargs sed -i 's/import org.junit.jupiter.api.Test/import kotlin.test.Test/'
```

Danach Rest-Vorkommen prüfen und nach dieser Tabelle von Hand mappen:

```bash
grep -rn 'org.junit' src/commonTest || echo 'KEINE junit-Reste'
```

| JUnit-API | kotlin.test-Ersatz |
|-----------|--------------------|
| `org.junit.jupiter.api.Test` | `kotlin.test.Test` |
| `assertThrows<T> { }` | `assertFailsWith<T> { }` (`kotlin.test.assertFailsWith`) |
| `org.junit.jupiter.api.Assertions.*` | `kotlin.test.*` (`assertEquals`, `assertTrue`, …) |
| `@Disabled` | `kotlin.test.Ignore` |
| `@BeforeEach` | `kotlin.test.BeforeTest` |

(Die Asserts in diesen Tests stammen bereits aus `kotlin.test` — meist reicht der Test-Import-Swap.)

- [ ] **Step 3: build.gradle.kts auf Multiplatform umschreiben**

Ersetze den kompletten Inhalt von `build.gradle.kts`:

```kotlin
plugins {
    kotlin("multiplatform") version "2.0.21"
    `maven-publish`
}

// io.github.<github-user> ist der von Sonatype auto-verifizierte Namespace.
// Bei JitPack wird die group durch com.github.<user> ersetzt — dort egal.
group = "io.github.gabriel-graf"
version = "0.2.0"

repositories { mavenCentral() }

kotlin {
    jvmToolchain(21)

    jvm()

    js(IR) {
        moduleName = "comic-cutter"
        useEsModules()
        browser()
        binaries.executable()
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("GuidedComic — Comic-Cutter")
            description.set(
                "Reiner-Kotlin Comic-Panel-Detektor (Profil-XY-Cut + Flood-Fallback) " +
                    "mit Lesereihenfolge und geführter Navigation. Host-unabhängig, kein AWT."
            )
            url.set("https://github.com/Gabriel-Graf/ComicGuide")
            licenses {
                license {
                    name.set("GNU Affero General Public License v3.0")
                    url.set("https://www.gnu.org/licenses/agpl-3.0.txt")
                }
            }
            developers { developer { id.set("gabriel-graf"); name.set("Gabriel Graf") } }
            scm {
                url.set("https://github.com/Gabriel-Graf/ComicGuide")
                connection.set("scm:git:https://github.com/Gabriel-Graf/ComicGuide.git")
                developerConnection.set("scm:git:ssh://git@github.com/Gabriel-Graf/ComicGuide.git")
            }
        }
    }
}
```

- [ ] **Step 4: Build über beide Targets verifizieren**

Run: `cd /home/gabriel/Documents/Projekte/GuidedComic && ./gradlew clean build --console=plain 2>&1 | tail -25`
Expected: `BUILD SUCCESSFUL`. Es laufen sowohl `jvmTest` als auch `jsTest` (Kotlin lädt Node automatisch). Alle bestehenden Detektor-Tests grün auf **beiden** Plattformen. Falls `jsTest` an einer Node-Heap-Grenze scheitert: in `gradle.properties` bleibt `-Xmx2g`; das betrifft nur die JVM, Node-Tests sind klein — bei Fehlern Fehlermeldung prüfen, nicht raten.

- [ ] **Step 5: JVM-Publishing-Koordinate gegenprüfen**

Run: `./gradlew publishToMavenLocal --console=plain 2>&1 | tail -5 && ls ~/.m2/repository/io/github/gabriel-graf/`
Expected: Module `comic-cutter` (Root-Metadata) **und** `comic-cutter-jvm` + `comic-cutter-js` unter Version `0.2.0`. Das `-jvm`-Artefakt ist das, was Komga via Gradle-Metadata auflöst.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(lib): Umstellung auf Kotlin-Multiplatform (jvm + js IR), Tests auf kotlin.test"
```

---

## Task 2: @JsExport-Fassade + jsTest

**Files:**
- Create: `src/jsMain/kotlin/com/panela/comiccutter/js/DetectorJs.kt`
- Create: `src/jsTest/kotlin/com/panela/comiccutter/js/DetectorJsTest.kt`

- [ ] **Step 1: jsTest für die Fassade schreiben (failing)**

`src/jsTest/kotlin/com/panela/comiccutter/js/DetectorJsTest.kt`:

```kotlin
package com.panela.comiccutter.js

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DetectorJsTest {

    /** Zwei texturierte Kacheln (links/rechts) auf weißem Grund mit heller Mittelgasse. */
    private fun twoPanelArgb(w: Int, h: Int): IntArray {
        val px = IntArray(w * h) { 0xFFFFFFFF.toInt() } // weiß = Gutter
        fun fill(x0: Int, x1: Int) {
            for (y in 20 until h - 20) for (x in x0 until x1) {
                val on = (x / 4 + y / 4) % 2 == 0
                px[y * w + x] = if (on) 0xFF5A5A5A.toInt() else 0xFFAAAAAA.toInt()
            }
        }
        fill(20, w / 2 - 30)        // linke Kachel
        fill(w / 2 + 30, w - 20)    // rechte Kachel
        return px
    }

    @Test
    fun detektiert_zwei_panels_in_LTR_reihenfolge() {
        val w = 1000; val h = 800
        val panels = detectPanels(w, h, twoPanelArgb(w, h), rtl = false)

        assertEquals(2, panels.size)
        // normalisiert in [0,1]
        assertTrue(panels.all { it.left in 0.0..1.0 && it.width in 0.0..1.0 })
        // LTR: order==1 liegt links von order==2
        val first = panels.first { it.order == 1 }
        val second = panels.first { it.order == 2 }
        assertTrue(first.left < second.left)
    }

    @Test
    fun rtl_dreht_die_reihenfolge() {
        val w = 1000; val h = 800
        val panels = detectPanels(w, h, twoPanelArgb(w, h), rtl = true)
        val first = panels.first { it.order == 1 }
        val second = panels.first { it.order == 2 }
        // RTL: order==1 liegt rechts
        assertTrue(first.left > second.left)
    }
}
```

- [ ] **Step 2: Test ausführen → muss fehlschlagen (detectPanels fehlt)**

Run: `./gradlew jsTest --console=plain 2>&1 | tail -20`
Expected: FAIL — `detectPanels`/`PanelJs` unauflösbar.

- [ ] **Step 3: Fassade implementieren**

`src/jsMain/kotlin/com/panela/comiccutter/js/DetectorJs.kt`:

```kotlin
package com.panela.comiccutter.js

import com.panela.comiccutter.PanelDetector
import com.panela.comiccutter.ReadingDirection
import com.panela.comiccutter.model.RenderedPage

/** Ein erkanntes Panel in normalisierten Seiten-Koordinaten (0..1), 1-basierte Lesereihenfolge. */
@JsExport
class PanelJs(
    val left: Double,
    val top: Double,
    val width: Double,
    val height: Double,
    val order: Int,
)

/**
 * Browser-API: erkennt Panels einer Seite.
 * @param argb ARGB-Pixel (0xAARRGGBB), Länge width*height, zeilenweise. In JS ein Int32Array.
 * @param rtl true = Manga (rechts→links), false = Comic (links→rechts).
 */
@JsExport
fun detectPanels(width: Int, height: Int, argb: IntArray, rtl: Boolean): Array<PanelJs> {
    if (width <= 0 || height <= 0 || argb.isEmpty()) return emptyArray()
    val page = RenderedPage(width, height, argb)
    val dir = if (rtl) ReadingDirection.RIGHT_TO_LEFT else ReadingDirection.LEFT_TO_RIGHT
    val w = width.toDouble()
    val h = height.toDouble()
    return PanelDetector().detect(page, dir)
        .mapIndexed { i, r ->
            PanelJs(
                left = r.x / w,
                top = r.y / h,
                width = r.width / w,
                height = r.height / h,
                order = i + 1,
            )
        }
        .toTypedArray()
}
```

- [ ] **Step 4: jsTest grün**

Run: `./gradlew jsTest --console=plain 2>&1 | tail -15`
Expected: PASS (beide Tests).

- [ ] **Step 5: Browser-Bundle bauen und Inhalt prüfen**

Run: `./gradlew jsBrowserDistribution --console=plain 2>&1 | tail -8 && find build/dist/js/productionExecutable -maxdepth 1 -type f`
Expected: Ein ES-Modul-Bundle (z. B. `comic-cutter.js` + ggf. `.js.map`) liegt unter `build/dist/js/productionExecutable/`. Mit `useEsModules()` exportiert es `detectPanels` und `PanelJs` als named exports.

- [ ] **Step 6: Node-Smoke-Test, dass die Exports aus reinem JS erreichbar sind**

```bash
cd /home/gabriel/Documents/Projekte/GuidedComic
node --input-type=module -e "import('./build/dist/js/productionExecutable/comic-cutter.js').then(m=>{const w=1000,h=800;const a=new Int32Array(w*h).fill(0xFFFFFFFF|0);for(let y=20;y<h-20;y++)for(let x=20;x<w/2-30;x++)a[y*w+x]=0xFF5A5A5A|0;for(let y=20;y<h-20;y++)for(let x=w/2+30;x<w-20;x++)a[y*w+x]=0xFF5A5A5A|0;const p=m.detectPanels(w,h,a,false);console.log('panels',p.length, p.map(x=>x.order));})"
```
Expected: `panels 2 [ 1, 2 ]` (oder ≥2). Falls der Bundle-Dateiname abweicht, aus dem `find`-Ergebnis von Step 5 übernehmen. Falls die Exports unter einem Namespace hängen (kein flacher named export), den tatsächlichen Zugriffspfad notieren — er wird in Task 4 (`detect.ts`) gebraucht.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat(js): @JsExport-Fassade detectPanels + jsTest (normalisierte Panels)"
```

---

## Task 3: Demo-Scaffold (Vite + TS, statisch)

**Files:**
- Create: `demo/package.json`, `demo/vite.config.ts`, `demo/tsconfig.json`, `demo/index.html`, `demo/src/main.ts`
- Modify: `.gitignore`

- [ ] **Step 1: .gitignore um Demo-Build + kopiertes Bundle ergänzen**

An `.gitignore` anhängen:

```
# Demo
demo/node_modules/
demo/dist/
demo/src/kotlin/
```

- [ ] **Step 2: package.json**

`demo/package.json`:

```json
{
  "name": "comicguide-demo",
  "private": true,
  "version": "0.0.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "tsc && vite build",
    "preview": "vite preview"
  },
  "devDependencies": {
    "typescript": "^5.6.0",
    "vite": "^5.4.0"
  }
}
```

- [ ] **Step 3: tsconfig.json + vite.config.ts + index.html**

`demo/tsconfig.json`:

```json
{
  "compilerOptions": {
    "target": "ES2020",
    "module": "ESNext",
    "moduleResolution": "bundler",
    "strict": true,
    "noUnusedLocals": true,
    "noUnusedParameters": true,
    "skipLibCheck": true,
    "lib": ["ES2020", "DOM", "DOM.Iterable"]
  },
  "include": ["src"]
}
```

`demo/vite.config.ts`:

```ts
import { defineConfig } from 'vite'

// base = Repo-Name, damit Asset-Pfade unter gabriel-graf.github.io/ComicGuide/ stimmen
export default defineConfig({
  base: '/ComicGuide/',
})
```

`demo/index.html`:

```html
<!doctype html>
<html lang="de">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>ComicGuide — Panel-Erkennung (Browser-Demo)</title>
  </head>
  <body>
    <div id="app"></div>
    <script type="module" src="/src/main.ts"></script>
  </body>
</html>
```

- [ ] **Step 4: Minimaler main.ts (Platzhalter, baut sauber)**

`demo/src/main.ts`:

```ts
const app = document.querySelector<HTMLDivElement>('#app')!
app.textContent = 'ComicGuide Demo — Setup OK'
```

- [ ] **Step 5: Install + Build verifizieren**

Run: `cd /home/gabriel/Documents/Projekte/GuidedComic/demo && npm install 2>&1 | tail -3 && npm run build 2>&1 | tail -6`
Expected: `npm install` ok, `vite build` erzeugt `demo/dist/` ohne TS-Fehler.

- [ ] **Step 6: Commit**

```bash
cd /home/gabriel/Documents/Projekte/GuidedComic
git add -A
git commit -m "feat(demo): Vite+TS-Scaffold (statisch, base=/ComicGuide/)"
```

---

## Task 4: Bundle-Bridge — RGBA→ARGB + Detektor-Aufruf (`detect.ts`)

**Files:**
- Create: `demo/src/detect.ts`
- Create: `demo/src/kotlin/.gitkeep` (Verzeichnis existiert; Bundle wird hineinkopiert)

- [ ] **Step 1: Kotlin/JS-Bundle in die Demo kopieren (lokaler Build-Schritt)**

```bash
cd /home/gabriel/Documents/Projekte/GuidedComic
./gradlew jsBrowserDistribution --console=plain >/dev/null 2>&1
mkdir -p demo/src/kotlin
cp build/dist/js/productionExecutable/comic-cutter.js demo/src/kotlin/
# Falls Map vorhanden:
cp build/dist/js/productionExecutable/comic-cutter.js.map demo/src/kotlin/ 2>/dev/null || true
ls demo/src/kotlin/
```
Expected: `comic-cutter.js` liegt in `demo/src/kotlin/`. (Dieser Kopierschritt wird in Task 7 durch die CI automatisiert.)

- [ ] **Step 2: Typdeklaration + Bridge schreiben**

`demo/src/detect.ts`:

```ts
// Kotlin/JS-Fassade (ES-Module, named exports aus @JsExport).
// @ts-expect-error – generiertes Bundle ohne .d.ts
import { detectPanels as ktDetectPanels } from './kotlin/comic-cutter.js'

export interface Panel {
  left: number
  top: number
  width: number
  height: number
  order: number
}

/** Max. Kantenlänge vor Erkennung — drosselt die Laufzeit, Output bleibt normalisiert. */
const MAX_EDGE = 1500

/**
 * Zeichnet das Bild (ggf. runterskaliert) auf ein Offscreen-Canvas, liest RGBA,
 * konvertiert nach ARGB-Int32Array und ruft den Kotlin-Detektor.
 */
export function detect(image: CanvasImageSource, srcW: number, srcH: number, rtl: boolean): Panel[] {
  const scale = Math.min(1, MAX_EDGE / Math.max(srcW, srcH))
  const w = Math.max(1, Math.round(srcW * scale))
  const h = Math.max(1, Math.round(srcH * scale))

  const canvas = document.createElement('canvas')
  canvas.width = w
  canvas.height = h
  const ctx = canvas.getContext('2d', { willReadFrequently: true })!
  ctx.drawImage(image, 0, 0, w, h)
  const rgba = ctx.getImageData(0, 0, w, h).data

  const argb = new Int32Array(w * h)
  for (let i = 0, p = 0; i < argb.length; i++, p += 4) {
    argb[i] = (rgba[p + 3] << 24) | (rgba[p] << 16) | (rgba[p + 1] << 8) | rgba[p + 2]
  }

  return ktDetectPanels(w, h, argb, rtl) as Panel[]
}
```

> Falls der Node-Smoke-Test (Task 2 Step 6) gezeigt hat, dass die Exports unter einem Namespace hängen statt flach, hier den Import entsprechend anpassen (z. B. `import pkg from './kotlin/comic-cutter.js'` und `pkg.detectPanels`).

- [ ] **Step 3: Bridge in main.ts temporär smoke-testen**

`demo/src/main.ts` ersetzen:

```ts
import { detect } from './detect'

const app = document.querySelector<HTMLDivElement>('#app')!
const img = new Image()
img.onload = () => {
  const panels = detect(img, img.naturalWidth, img.naturalHeight, false)
  app.textContent = `Erkannt: ${panels.length} Panels`
}
img.onerror = () => { app.textContent = 'Bild-Ladefehler' }
// 1x1-Platzhalter nur für den Build-Smoke; echte Bilder kommen in Task 5/6
img.src =
  'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M8AAAMBAQDJ/pLvAAAAAElFTkSuQmCC'
```

- [ ] **Step 4: Build verifizieren**

Run: `cd demo && npm run build 2>&1 | tail -6`
Expected: kein TS-Fehler, `dist/` gebaut. (Das 1×1-Bild liefert 0 Panels — geprüft wird nur, dass Import/Build/Bundle zusammenpassen.)

- [ ] **Step 5: Commit**

```bash
cd /home/gabriel/Documents/Projekte/GuidedComic
git add -A
git commit -m "feat(demo): Kotlin/JS-Bridge — RGBA→ARGB + detect()"
```

---

## Task 5: UI — Auswahl/Upload, Overlay-Rendering, RTL-Toggle

**Files:**
- Create: `demo/src/render.ts`
- Modify: `demo/src/main.ts`
- Create: `demo/src/style.css`

- [ ] **Step 1: Overlay-Renderer**

`demo/src/render.ts`:

```ts
import type { Panel } from './detect'

const COLORS = ['#2d7dff', '#ff2d55', '#39ff14', '#ffb300', '#b026ff', '#00d4c8']

/** Baut ein Overlay (absolut positionierte Boxen + Nummern) über dem angezeigten Bild. */
export function renderOverlay(host: HTMLElement, panels: Panel[]): void {
  host.querySelectorAll('.panel-box').forEach((n) => n.remove())
  panels.forEach((p) => {
    const box = document.createElement('div')
    box.className = 'panel-box'
    const c = COLORS[(p.order - 1) % COLORS.length]
    box.style.cssText =
      `left:${p.left * 100}%;top:${p.top * 100}%;width:${p.width * 100}%;height:${p.height * 100}%;` +
      `border-color:${c};box-shadow:0 0 0 1px rgba(0,0,0,.6);`
    const badge = document.createElement('span')
    badge.className = 'panel-badge'
    badge.style.background = c
    badge.textContent = String(p.order)
    box.appendChild(badge)
    host.appendChild(box)
  })
}
```

- [ ] **Step 2: style.css**

`demo/src/style.css`:

```css
:root { --bg:#0e0f11; --surface:#1a1b22; --border:#2a2c38; --text:#e2e4ec; --muted:#8b8fa8; }
* { box-sizing: border-box; }
body { margin:0; background:var(--bg); color:var(--text); font-family:system-ui,sans-serif; }
.wrap { max-width:1100px; margin:0 auto; padding:20px; }
h1 { font-size:18px; letter-spacing:.04em; }
.controls { display:flex; gap:10px; align-items:center; flex-wrap:wrap; margin:14px 0; }
.controls select, .controls button, .controls label {
  background:var(--surface); color:var(--text); border:1px solid var(--border);
  border-radius:6px; padding:7px 12px; font-size:13px; cursor:pointer;
}
.stage { position:relative; display:inline-block; line-height:0; border:1px solid var(--border); }
.stage img { display:block; max-width:100%; max-height:78vh; }
.panel-box { position:absolute; border:3px solid; pointer-events:none; }
.panel-badge { position:absolute; top:3px; left:4px; font:700 12px ui-monospace,monospace;
  color:#fff; padding:1px 5px; border-radius:2px; text-shadow:0 1px 2px rgba(0,0,0,.5); }
.hint { color:var(--muted); font-size:13px; margin-top:10px; }
.drop { outline:2px dashed var(--border); }
```

- [ ] **Step 3: main.ts — vollständige UI**

`demo/src/main.ts`:

```ts
import './style.css'
import { detect, type Panel } from './detect'
import { renderOverlay } from './render'

interface ComicEntry { file: string; title: string; year: number }

const app = document.querySelector<HTMLDivElement>('#app')!
app.innerHTML = `
  <div class="wrap">
    <h1>ComicGuide — Panel-Erkennung im Browser</h1>
    <div class="controls">
      <select id="sample"></select>
      <label>Upload <input id="upload" type="file" accept="image/*" hidden></label>
      <label><input id="rtl" type="checkbox"> Rechts→Links (Manga)</label>
      <span id="count" class="hint"></span>
    </div>
    <div id="stage" class="stage"><img id="page" alt="Comicseite"></div>
    <p class="hint">Bild ziehen &amp; ablegen oder „Upload" — die Erkennung läuft komplett lokal im Browser.</p>
  </div>`

const sampleSel = app.querySelector<HTMLSelectElement>('#sample')!
const uploadInput = app.querySelector<HTMLInputElement>('#upload')!
const rtlBox = app.querySelector<HTMLInputElement>('#rtl')!
const stage = app.querySelector<HTMLDivElement>('#stage')!
const pageImg = app.querySelector<HTMLImageElement>('#page')!
const count = app.querySelector<HTMLSpanElement>('#count')!

function runDetection() {
  if (!pageImg.naturalWidth) return
  try {
    const panels: Panel[] = detect(pageImg, pageImg.naturalWidth, pageImg.naturalHeight, rtlBox.checked)
    renderOverlay(stage, panels)
    count.textContent = panels.length === 0 ? 'keine Panels gefunden' : `${panels.length} Panels`
  } catch (e) {
    count.textContent = 'Fehler bei der Erkennung'
    console.error(e)
  }
}

function loadSrc(src: string) {
  pageImg.onload = runDetection
  pageImg.onerror = () => { count.textContent = 'Bild konnte nicht geladen werden' }
  pageImg.src = src
}

// Beispiele laden
fetch(`${import.meta.env.BASE_URL}comics.json`)
  .then((r) => r.json())
  .then((list: ComicEntry[]) => {
    list.forEach((c, i) => {
      const o = document.createElement('option')
      o.value = c.file
      o.textContent = `${c.title} (${c.year})`
      if (i === 0) o.selected = true
      sampleSel.appendChild(o)
    })
    if (list.length) loadSrc(`${import.meta.env.BASE_URL}comics/${list[0].file}`)
  })
  .catch(() => { count.textContent = 'Beispiele nicht gefunden — bitte Bild hochladen' })

sampleSel.addEventListener('change', () =>
  loadSrc(`${import.meta.env.BASE_URL}comics/${sampleSel.value}`),
)
rtlBox.addEventListener('change', runDetection)

uploadInput.addEventListener('change', () => {
  const f = uploadInput.files?.[0]
  if (!f) return
  if (!f.type.startsWith('image/')) { count.textContent = 'Bitte eine Bilddatei wählen'; return }
  loadSrc(URL.createObjectURL(f))
})
// Klick auf das „Upload"-Label triggert den versteckten Input automatisch (label[for]-Ersatz)
app.querySelector('label')!.addEventListener('click', () => uploadInput.click())

// Drag & Drop
;['dragover', 'dragenter'].forEach((ev) =>
  stage.addEventListener(ev, (e) => { e.preventDefault(); stage.classList.add('drop') }),
)
;['dragleave', 'drop'].forEach((ev) =>
  stage.addEventListener(ev, () => stage.classList.remove('drop')),
)
stage.addEventListener('drop', (e) => {
  e.preventDefault()
  const f = (e as DragEvent).dataTransfer?.files?.[0]
  if (f && f.type.startsWith('image/')) loadSrc(URL.createObjectURL(f))
})
```

- [ ] **Step 4: Build verifizieren**

Run: `cd demo && npm run build 2>&1 | tail -6`
Expected: kein TS-Fehler, `dist/` gebaut.

- [ ] **Step 5: Manuelle Sichtprüfung im Dev-Server**

Run: `cd demo && npm run dev` (im Hintergrund), dann Browser auf die angezeigte URL.
Expected: Sobald in Task 6 Beispielbilder vorhanden sind, erscheinen Overlay-Boxen mit Nummern; RTL-Toggle dreht die Nummerierung; Upload/Drag&Drop eines eigenen Comics zeigt Panels. (Ohne Beispiele: Upload-Pfad testen.)

- [ ] **Step 6: Commit**

```bash
cd /home/gabriel/Documents/Projekte/GuidedComic
git add -A
git commit -m "feat(demo): UI — Auswahl/Upload/Drag&Drop, Overlay-Rendering, RTL-Toggle"
```

---

## Task 6: Beispiel-Comics (Public Domain) + Provenance

**Files:**
- Create: `demo/comics/*.jpg` (~10 Seiten)
- Create: `demo/comics.json`
- Create: `demo/PROVENANCE.md`

> **Human-Checkpoint:** Diese Aufgabe braucht Freigabe der konkreten Titel, BEVOR Bilder eingebunden werden (Lizenz-Sorgfalt + Provenance-Pflicht). Der ausführende Agent stoppt nach Step 1 und legt dem Orchestrator/Nutzer die Liste vor.

- [ ] **Step 1: 10 belegt-gemeinfreie US-Golden-Age-Titel vorschlagen**

Kriterium: Erstveröffentlichung in den USA vor 1964 **ohne** Copyright-Renewal → Public Domain. Quelle: Digital Comic Museum / Comic Book Plus (beide kuratieren ausschließlich PD-Material). Pro Titel **eine** panelreiche Innenseite. Liste mit Permalink je Seite erstellen und zur Freigabe vorlegen. Kandidaten-Pool (panelreich, unstrittig PD): frühe Hefte von *Crime Does Not Pay*, *Daredevil Comics (Lev Gleason)*, *Black Cat*, *Captain Flash*, *Phantom Lady*, *Police Comics*, *Zip Comics*, *Silver Streak*, *Boy Comics*, *Plastic Man*. **Keine** Disney/DC/Marvel-Figuren (Renewal-Risiko).

- [ ] **Step 2: Nach Freigabe — Seiten laden, runterskalieren, ablegen**

Je freigegebene Seite herunterladen und auf längste Kante ~1500 px, JPG q≈80 reduzieren:

```bash
cd /home/gabriel/Documents/Projekte/GuidedComic/demo/comics
# Beispiel je Datei (ImageMagick):
convert quelle.jpg -resize '1500x1500>' -quality 80 01-titel.jpg
```
Dateinamen `NN-kurztitel.jpg`. Ziel: Gesamtgröße `demo/comics/` ≲ 4 MB.

- [ ] **Step 3: comics.json**

`demo/comics.json` (Beispielstruktur, mit echten freigegebenen Titeln füllen):

```json
[
  { "file": "01-crime-does-not-pay.jpg", "title": "Crime Does Not Pay #24", "year": 1942 }
]
```

- [ ] **Step 4: PROVENANCE.md**

`demo/PROVENANCE.md` — je Quelle Pflichtfelder:

```markdown
# Demo-Beispielseiten — Provenance

Alle Seiten sind US-Golden-Age-Comics (Erstveröffentlichung vor 1964 ohne
Copyright-Renewal → Public Domain). Verkleinert für die Demo (≤1500px, JPG q80).

| Datei | Titel / Heft | Jahr | Quelle (Permalink) | Lizenz | Erfasst |
|-------|--------------|------|--------------------|--------|---------|
| 01-crime-does-not-pay.jpg | Crime Does Not Pay #24 | 1942 | <permalink> | Public Domain (kein Renewal) | 2026-06-11 |

## Risk-Notiz
Public-Domain-Status beruht auf fehlendem Copyright-Renewal (US-Recht, Werke
vor 1964). Quelle DCM/Comic Book Plus kuratiert ausschließlich PD-Material.
Keine markengeschützten Figuren (Disney/DC/Marvel) enthalten.

Letzte Komplettrevision: 2026-06-11 (gabriel-graf)
```

- [ ] **Step 5: Build + Sichtprüfung**

Run: `cd demo && npm run build 2>&1 | tail -4`
Expected: Build ok, `comics/` + `comics.json` landen in `dist/`. Dev-Server zeigt die Beispiele mit Overlay.

- [ ] **Step 6: Commit**

```bash
cd /home/gabriel/Documents/Projekte/GuidedComic
git add -A
git commit -m "feat(demo): ~10 Public-Domain-Beispielseiten + Provenance"
```

---

## Task 7: GitHub Actions → Pages

**Files:**
- Create: `.github/workflows/pages.yml`

- [ ] **Step 1: Workflow schreiben**

`.github/workflows/pages.yml`:

```yaml
name: Deploy Demo to Pages

on:
  push:
    branches: [main]
  workflow_dispatch:

permissions:
  contents: read
  pages: write
  id-token: write

concurrency:
  group: pages
  cancel-in-progress: true

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'

      - name: Kotlin/JS-Bundle bauen
        run: ./gradlew jsBrowserDistribution --no-daemon

      - name: Bundle in die Demo kopieren
        run: |
          mkdir -p demo/src/kotlin
          cp build/dist/js/productionExecutable/comic-cutter.js demo/src/kotlin/
          cp build/dist/js/productionExecutable/comic-cutter.js.map demo/src/kotlin/ 2>/dev/null || true

      - uses: actions/setup-node@v4
        with:
          node-version: '20'

      - name: Demo bauen
        working-directory: demo
        run: |
          npm ci
          npm run build

      - uses: actions/upload-pages-artifact@v3
        with:
          path: demo/dist

  deploy:
    needs: build
    runs-on: ubuntu-latest
    environment:
      name: github-pages
      url: ${{ steps.deployment.outputs.page_url }}
    steps:
      - id: deployment
        uses: actions/deploy-pages@v4
```

> `npm ci` braucht eine `demo/package-lock.json` — sie entsteht in Task 3 Step 5 (`npm install`) und muss committet sein. Prüfen: `git ls-files demo/package-lock.json`.

- [ ] **Step 2: Workflow-YAML lokal validieren**

Run: `cd /home/gabriel/Documents/Projekte/GuidedComic && python3 -c "import yaml,sys; yaml.safe_load(open('.github/workflows/pages.yml')); print('YAML ok')"`
Expected: `YAML ok`.

- [ ] **Step 3: package-lock sicherstellen**

Run: `git ls-files demo/package-lock.json`
Expected: Pfad wird gelistet. Falls leer: `cd demo && npm install` und committen.

- [ ] **Step 4: Commit + Push (löst den ersten Pages-Build aus)**

```bash
cd /home/gabriel/Documents/Projekte/GuidedComic
git add -A
git commit -m "ci: GitHub Actions Demo-Deploy nach Pages"
git push origin main
```

- [ ] **Step 5: Pages in den Repo-Settings aktivieren (manuell, einmalig)**

GitHub → Repo Settings → Pages → Source = **GitHub Actions**. Danach Actions-Run abwarten; URL prüfen: `https://gabriel-graf.github.io/ComicGuide/`.
Expected: Demo lädt, Beispielseite zeigt Overlay-Panels.

---

## Task 8: README + Doku finalisieren

**Files:**
- Modify: `README.md`

- [ ] **Step 1: README um Demo-Link + 0.2.0-Hinweis ergänzen**

In `README.md` oben einen Abschnitt einfügen:

```markdown
## Live-Demo

Panel-Erkennung direkt im Browser (kein Backend):
**https://gabriel-graf.github.io/ComicGuide/**
Beispiel-Comics (Public Domain) oder eigenes Bild hochladen.
```

Und im Einbinden-Abschnitt die Version auf `0.2.0` ziehen sowie den KMP-Hinweis ergänzen:

```markdown
> Ab 0.2.0 ist die Lib Kotlin-Multiplatform. Gradle-Konsumenten (z. B. Komga)
> nutzen weiter `io.github.gabriel-graf:comic-cutter:0.2.0` — Gradle löst via
> Modul-Metadata automatisch auf das `-jvm`-Artefakt auf.
```

- [ ] **Step 2: Verifizieren, dass alles grün ist**

Run: `cd /home/gabriel/Documents/Projekte/GuidedComic && ./gradlew build --console=plain 2>&1 | tail -5 && cd demo && npm run build 2>&1 | tail -4`
Expected: beide Builds `SUCCESSFUL`/ohne Fehler.

- [ ] **Step 3: Commit + Push**

```bash
cd /home/gabriel/Documents/Projekte/GuidedComic
git add -A
git commit -m "docs: Live-Demo-Link + KMP-0.2.0-Hinweis im README"
git push origin main
```

- [ ] **Step 4: Tag v0.2.0 (KMP-Release mit JS-Target)**

```bash
git tag -a v0.2.0 -m "comic-cutter 0.2.0 — Kotlin-Multiplatform (jvm + js), Browser-Demo"
git push origin v0.2.0
```

---

## Self-Review-Notizen (Plan ggü. Spec)

- **Spec-Abdeckung:** KMP-Umstellung (T1), JS-Fassade (T2), statische Demo + Datenfluss RGBA→ARGB + Downscale 1500px (T3–T5), RTL-Toggle + Fehlerfälle (T5), PD-Comics + Provenance (T6), Pages-Deploy (T7), README/Version (T8). Alle Spec-Abschnitte haben eine Task.
- **Testing:** Bestehende Tests laufen nach T1 auf jvm+js; Fassade hat eigene jsTests (T2) inkl. Node-Smoke. Demo-UI: manuelle Sichtprüfung (visuell, YAGNI) — entspricht Spec.
- **Offen/Checkpoint:** Konkrete Titel (T6 Step 1, Human-Gate); Default-Leserichtung = LTR (Checkbox unchecked).
- **Interop-Risiko** (named export vs. Namespace) ist explizit in T2 Step 6 + T4 Step 2 abgefangen.
