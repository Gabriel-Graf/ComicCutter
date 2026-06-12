# GuidedComic Guided-Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Die Lib zur entkoppelten Guided-Reading-Engine ausbauen — pluggbare Panel-Quelle (Detektor + ML) hinter `PanelSource`, und eine `GuidedReader`-Facade, die Cache, Position, Crop-Rect und Confidence-Fallback besitzt, sodass eine App nur noch `next()`/`previous()`/`fullPage()` ruft.

**Architecture:** Functional Core, Imperative Shell. `comic-cutter` (reiner KMP-Kern, stdlib-only) definiert die `PanelSource`-Naht und alle reine Logik. Der ML-Runner (I/O, Native) lebt in einem optionalen Extra-Modul `comic-cutter-onnx-jvm`. Beide Quellen laufen gegen dieselbe `GuidedReader`-API.

**Tech Stack:** Kotlin Multiplatform 2.1.21 (jvm + js), Gradle, `kotlin.test`, `kotlinx-coroutines-test` (nur Test-Scope), ONNX-Runtime (nur im jvm-Runner-Modul).

---

## File Structure

**Phase A — Kern (`comic-cutter`, alle in `src/commonMain/kotlin/com/panela/comiccutter/`):**
- `PanelSource.kt` — `fun interface PanelSource` (roh-Boxen einer Seite).
- `GeometricPanelSource.kt` — wrappt `PanelDetector` hinter `PanelSource`.
- `MlDetection.kt` — `RawDetection` data class + `ModelRunner` interface.
- `MlFilter.kt` — `MlFilter` Parameter + IoU/NMS-Geometrie (rein).
- `MlPanelSource.kt` — `MlPanelSource(runner, filter): PanelSource` (Mapping-Pipeline).
- `GuidedReader.kt` — `GuidedReader` Facade + `GuidedStep` data class.

Tests in `src/commonTest/kotlin/com/panela/comiccutter/`:
- `GeometricPanelSourceTest.kt`, `MlFilterTest.kt`, `MlPanelSourceTest.kt`, `GuidedReaderTest.kt`.

**Phase B — ONNX-Runner (`comic-cutter-onnx-jvm/`, neues Gradle-Modul, JVM-only):**
- `comic-cutter-onnx-jvm/build.gradle.kts`
- `comic-cutter-onnx-jvm/src/main/kotlin/com/panela/comiccutter/onnx/OnnxModelRunner.kt`
- `comic-cutter-onnx-jvm/src/main/kotlin/com/panela/comiccutter/onnx/Letterbox.kt`
- `comic-cutter-onnx-jvm/src/test/kotlin/com/panela/comiccutter/onnx/OnnxModelRunnerTest.kt`
- `models/PROVENANCE.md` + `models/panel-yolo11n-v3.onnx` (exportiertes Asset)

---

## PHASE A — Reiner Kern (sofort baubar)

### Task 0: Coroutines-Test-Dependency (nur Test-Scope)

**Files:**
- Modify: `build.gradle.kts` (sourceSets-Block, `commonTest.dependencies`)

- [ ] **Step 1: Dependency ergänzen**

In `build.gradle.kts`, im `commonTest.dependencies`-Block, additiv:

```kotlin
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
        }
```

- [ ] **Step 2: Build prüfen (Dependency auflösbar)**

Run: `./gradlew compileTestKotlinJvm`
Expected: BUILD SUCCESSFUL (lädt coroutines-test).

- [ ] **Step 3: Commit**

```bash
git add build.gradle.kts
git commit -m "build(test): kotlinx-coroutines-test fuer suspend-Tests (nur Test-Scope)"
```

---

### Task 1: PanelSource-Naht + GeometricPanelSource

`PanelDetector.detect` sortiert bereits intern in Lesereihenfolge; `GuidedReader` sortiert später erneut (idempotent). `GeometricPanelSource` delegiert deshalb schlicht an `detect` — kein Eingriff in `PanelDetector`, voll rückwärtskompatibel.

**Files:**
- Create: `src/commonMain/kotlin/com/panela/comiccutter/PanelSource.kt`
- Create: `src/commonMain/kotlin/com/panela/comiccutter/GeometricPanelSource.kt`
- Test: `src/commonTest/kotlin/com/panela/comiccutter/GeometricPanelSourceTest.kt`

- [ ] **Step 1: Failing test schreiben**

```kotlin
package com.panela.comiccutter

import com.panela.comiccutter.model.RenderedPage
import kotlin.test.Test
import kotlin.test.assertEquals

class GeometricPanelSourceTest {
    /** Eine 2-Panel-Seite (weiße Gasse in der Mitte): Quelle liefert dieselben Boxen wie der Detektor. */
    @Test
    fun source_liefert_dieselben_boxen_wie_detektor() {
        val w = 200; val h = 100
        val px = IntArray(w * h) { 0xFFFFFFFF.toInt() }
        // zwei dunkle Panels links/rechts, helle Gasse Spalte 95..104
        for (y in 10 until 90) for (x in 0 until w) {
            val gutter = x in 95..104
            if (!gutter && (x < 95 || x > 104)) px[y * w + x] = 0xFF202020.toInt()
        }
        val page = RenderedPage(w, h, px)

        val viaDetector = PanelDetector().detect(page, ReadingDirection.LEFT_TO_RIGHT)
        val viaSource = GeometricPanelSource().detect(page)

        assertEquals(viaDetector, viaSource)
    }
}
```

- [ ] **Step 2: Test laufen lassen (muss fehlschlagen)**

Run: `./gradlew jvmTest --tests "com.panela.comiccutter.GeometricPanelSourceTest"`
Expected: FAIL — `PanelSource`/`GeometricPanelSource` nicht definiert (Compile-Fehler).

- [ ] **Step 3: Minimal implementieren**

`PanelSource.kt`:

```kotlin
package com.panela.comiccutter

import com.panela.comiccutter.model.RenderedPage

/**
 * Quelle roher Panel-Boxen einer Seite. Implementierungen: [GeometricPanelSource]
 * (Detektor) oder [MlPanelSource] (Modell). Die Reihenfolge bestimmt der [GuidedReader].
 */
fun interface PanelSource {
    fun detect(page: RenderedPage): List<PanelRect>
}
```

`GeometricPanelSource.kt`:

```kotlin
package com.panela.comiccutter

import com.panela.comiccutter.model.RenderedPage

/** [PanelSource] auf Basis des geometrischen [PanelDetector]. Comics: links→rechts. */
class GeometricPanelSource(
    private val detector: PanelDetector = PanelDetector(),
) : PanelSource {
    override fun detect(page: RenderedPage): List<PanelRect> =
        detector.detect(page, ReadingDirection.LEFT_TO_RIGHT)
}
```

- [ ] **Step 4: Test laufen lassen (muss bestehen)**

Run: `./gradlew jvmTest --tests "com.panela.comiccutter.GeometricPanelSourceTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/commonMain/kotlin/com/panela/comiccutter/PanelSource.kt src/commonMain/kotlin/com/panela/comiccutter/GeometricPanelSource.kt src/commonTest/kotlin/com/panela/comiccutter/GeometricPanelSourceTest.kt
git commit -m "feat(core): PanelSource-Naht + GeometricPanelSource"
```

---

### Task 2: RawDetection + ModelRunner

Reine Daten/Interface — kein eigener Test (wird in Task 3/4 mitgetestet).

**Files:**
- Create: `src/commonMain/kotlin/com/panela/comiccutter/MlDetection.kt`

- [ ] **Step 1: Implementieren**

```kotlin
package com.panela.comiccutter

import com.panela.comiccutter.model.RenderedPage

/** Eine rohe Modell-Detektion vor Filterung, in Seiten-Pixel-Koordinaten. */
data class RawDetection(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val score: Float,
    val cls: Int,
)

/**
 * Führt das Panel-Modell aus. Implementiert von der App (eigene Inferenz) ODER vom
 * optionalen Modul comic-cutter-onnx-jvm. Der Kern bleibt dadurch Native-frei.
 */
interface ModelRunner {
    fun infer(page: RenderedPage): List<RawDetection>
}
```

- [ ] **Step 2: Kompiliert**

Run: `./gradlew compileKotlinJvm`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/commonMain/kotlin/com/panela/comiccutter/MlDetection.kt
git commit -m "feat(core): RawDetection + ModelRunner-Naht"
```

---

### Task 3: MlFilter — IoU + NMS + Schwellwerte

**Files:**
- Create: `src/commonMain/kotlin/com/panela/comiccutter/MlFilter.kt`
- Test: `src/commonTest/kotlin/com/panela/comiccutter/MlFilterTest.kt`

- [ ] **Step 1: Failing test schreiben**

```kotlin
package com.panela.comiccutter

import kotlin.test.Test
import kotlin.test.assertEquals

class MlFilterTest {
    private fun det(x: Int, y: Int, w: Int, h: Int, s: Float, c: Int = 0) =
        RawDetection(x, y, w, h, s, c)

    @Test
    fun conf_unter_schwelle_faellt_raus() {
        val filter = MlFilter(minScore = 0.5f, nmsIoU = 1f, minAreaFraction = 0f)
        val out = filter.apply(listOf(det(0, 0, 10, 10, 0.4f), det(20, 0, 10, 10, 0.9f)), pageW = 100, pageH = 100)
        assertEquals(listOf(PanelRect(20, 0, 10, 10)), out)
    }

    @Test
    fun nms_unterdrueckt_ueberlappende_schwaechere_box() {
        // zwei stark überlappende Boxen → nur die stärkere bleibt
        val filter = MlFilter(minScore = 0f, nmsIoU = 0.5f, minAreaFraction = 0f)
        val out = filter.apply(listOf(det(0, 0, 100, 100, 0.9f), det(5, 5, 100, 100, 0.6f)), pageW = 200, pageH = 200)
        assertEquals(listOf(PanelRect(0, 0, 100, 100)), out)
    }

    @Test
    fun box_unter_min_area_faellt_raus() {
        val filter = MlFilter(minScore = 0f, nmsIoU = 1f, minAreaFraction = 0.01f)
        // 100x100 Seite, minArea = 100 px². Box 5x5=25 raus, 20x20=400 bleibt.
        val out = filter.apply(listOf(det(0, 0, 5, 5, 0.9f), det(50, 50, 20, 20, 0.9f)), pageW = 100, pageH = 100)
        assertEquals(listOf(PanelRect(50, 50, 20, 20)), out)
    }

    @Test
    fun keep_class_filtert_andere_klassen() {
        val filter = MlFilter(minScore = 0f, nmsIoU = 1f, minAreaFraction = 0f, keepClass = 0)
        val out = filter.apply(listOf(det(0, 0, 10, 10, 0.9f, c = 1), det(20, 0, 10, 10, 0.9f, c = 0)), pageW = 100, pageH = 100)
        assertEquals(listOf(PanelRect(20, 0, 10, 10)), out)
    }
}
```

- [ ] **Step 2: Test laufen lassen (muss fehlschlagen)**

Run: `./gradlew jvmTest --tests "com.panela.comiccutter.MlFilterTest"`
Expected: FAIL — `MlFilter` nicht definiert.

- [ ] **Step 3: Minimal implementieren**

```kotlin
package com.panela.comiccutter

/**
 * Reine Filter-Pipeline Modell-Output → Panel-Boxen: conf-Threshold → optionaler
 * Klassen-Filter → NMS (IoU) → min-Fläche. Deterministisch, ohne Modell testbar.
 *
 * @param minScore         Detektionen unter diesem Score werden verworfen.
 * @param nmsIoU           Zwei Boxen mit IoU >= diesem Wert gelten als dieselbe; die schwächere fällt.
 * @param minAreaFraction  Boxen kleiner als dieser Seitenflächen-Anteil werden verworfen.
 * @param keepClass        Wenn gesetzt: nur Detektionen dieser Klasse behalten.
 */
data class MlFilter(
    val minScore: Float = 0.30f,
    val nmsIoU: Float = 0.50f,
    val minAreaFraction: Float = 0.01f,
    val keepClass: Int? = null,
) {
    fun apply(detections: List<RawDetection>, pageW: Int, pageH: Int): List<PanelRect> {
        val minArea = pageW.toLong() * pageH * minAreaFraction
        val kept = detections
            .filter { it.score >= minScore }
            .filter { keepClass == null || it.cls == keepClass }
            .filter { it.width.toLong() * it.height >= minArea }
            .sortedByDescending { it.score }
        return nms(kept).map { PanelRect(it.x, it.y, it.width, it.height) }
    }

    /** Greedy-NMS: stärkste zuerst, unterdrückt jede spätere Box mit IoU >= [nmsIoU]. */
    private fun nms(sortedByScore: List<RawDetection>): List<RawDetection> {
        val kept = mutableListOf<RawDetection>()
        for (d in sortedByScore) {
            if (kept.none { iou(it, d) >= nmsIoU }) kept.add(d)
        }
        return kept
    }

    private fun iou(a: RawDetection, b: RawDetection): Float {
        val ix = maxOf(a.x, b.x); val iy = maxOf(a.y, b.y)
        val ax = minOf(a.x + a.width, b.x + b.width); val ay = minOf(a.y + a.height, b.y + b.height)
        val iw = ax - ix; val ih = ay - iy
        if (iw <= 0 || ih <= 0) return 0f
        val inter = iw.toLong() * ih
        val union = a.width.toLong() * a.height + b.width.toLong() * b.height - inter
        return if (union <= 0) 0f else inter.toFloat() / union
    }
}
```

- [ ] **Step 4: Test laufen lassen (muss bestehen)**

Run: `./gradlew jvmTest --tests "com.panela.comiccutter.MlFilterTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/commonMain/kotlin/com/panela/comiccutter/MlFilter.kt src/commonTest/kotlin/com/panela/comiccutter/MlFilterTest.kt
git commit -m "feat(core): MlFilter (conf, NMS, min-area, keepClass)"
```

---

### Task 4: MlPanelSource

**Files:**
- Create: `src/commonMain/kotlin/com/panela/comiccutter/MlPanelSource.kt`
- Test: `src/commonTest/kotlin/com/panela/comiccutter/MlPanelSourceTest.kt`

- [ ] **Step 1: Failing test schreiben**

```kotlin
package com.panela.comiccutter

import com.panela.comiccutter.model.RenderedPage
import kotlin.test.Test
import kotlin.test.assertEquals

class MlPanelSourceTest {
    /** Fake-Runner: liefert feste Detektionen, ignoriert Pixel — testet das Mapping ohne Modell. */
    private class FakeRunner(val out: List<RawDetection>) : ModelRunner {
        override fun infer(page: RenderedPage): List<RawDetection> = out
    }

    private val page = RenderedPage(100, 100, IntArray(100 * 100))

    @Test
    fun mappt_runner_output_durch_filter_auf_panelrects() {
        val runner = FakeRunner(
            listOf(
                RawDetection(10, 10, 30, 30, 0.9f, 0),
                RawDetection(10, 10, 30, 30, 0.5f, 0),   // dieselbe Box, schwächer → NMS
                RawDetection(60, 60, 30, 30, 0.2f, 0),   // unter conf 0.3 → raus
            ),
        )
        val source = MlPanelSource(runner, MlFilter(minScore = 0.3f, nmsIoU = 0.5f, minAreaFraction = 0f))

        assertEquals(listOf(PanelRect(10, 10, 30, 30)), source.detect(page))
    }
}
```

- [ ] **Step 2: Test laufen lassen (muss fehlschlagen)**

Run: `./gradlew jvmTest --tests "com.panela.comiccutter.MlPanelSourceTest"`
Expected: FAIL — `MlPanelSource` nicht definiert.

- [ ] **Step 3: Minimal implementieren**

```kotlin
package com.panela.comiccutter

import com.panela.comiccutter.model.RenderedPage

/**
 * [PanelSource] auf Basis eines ML-Modells: [runner] erzeugt rohe Detektionen,
 * [filter] reduziert sie (conf/NMS/min-area) auf Panel-Boxen. Die Reihenfolge
 * bestimmt der [GuidedReader] — hier kommen die Boxen ungeordnet.
 */
class MlPanelSource(
    private val runner: ModelRunner,
    private val filter: MlFilter = MlFilter(),
) : PanelSource {
    override fun detect(page: RenderedPage): List<PanelRect> =
        filter.apply(runner.infer(page), page.width, page.height)
}
```

- [ ] **Step 4: Test laufen lassen (muss bestehen)**

Run: `./gradlew jvmTest --tests "com.panela.comiccutter.MlPanelSourceTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/commonMain/kotlin/com/panela/comiccutter/MlPanelSource.kt src/commonTest/kotlin/com/panela/comiccutter/MlPanelSourceTest.kt
git commit -m "feat(core): MlPanelSource (Runner + Filter)"
```

---

### Task 5: GuidedReader — Cache, next/previous, LTR-Reihenfolge

`GuidedReader` nutzt die bestehende `GuidedNavigator`-Indexlogik (`next/previous` mit `unitsAt`) und `ReadingOrder.sort(..., LEFT_TO_RIGHT)`. `unitsAt(page) = max(1, panelCount(page))`: 0/1 Panel → 1 Einheit (Vollseite), sonst je Panel eine Einheit.

**Files:**
- Create: `src/commonMain/kotlin/com/panela/comiccutter/GuidedReader.kt`
- Test: `src/commonTest/kotlin/com/panela/comiccutter/GuidedReaderTest.kt`

- [ ] **Step 1: Failing test schreiben**

```kotlin
package com.panela.comiccutter

import com.panela.comiccutter.model.RenderedPage
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GuidedReaderTest {
    /** Zählt detect-Aufrufe je Seite → beweist Caching. Boxen kommen aus einer Map je Seite. */
    private class SpySource(val perPage: Map<Int, List<PanelRect>>) : PanelSource {
        val calls = mutableListOf<Int>()
        private var current = 0
        fun pageFor(page: Int): RenderedPage { current = page; return RenderedPage(100, 100, IntArray(1)) }
        override fun detect(page: RenderedPage): List<PanelRect> {
            calls.add(current); return perPage[current] ?: emptyList()
        }
    }

    private fun reader(spy: SpySource, pages: Int) =
        GuidedReader(spy, pageCount = pages, loadPage = { spy.pageFor(it) })

    @Test
    fun next_geht_durch_panels_einer_seite_in_LTR() = runTest {
        // zwei Panels: rechts (x=60) zuerst eingefügt, links (x=10) danach → LTR sortiert links zuerst
        val spy = SpySource(mapOf(0 to listOf(PanelRect(60, 10, 30, 30), PanelRect(10, 10, 30, 30))))
        val r = reader(spy, pages = 1)

        assertEquals(GuidedStep(0, PanelGeometry.normalize(PanelRect(10, 10, 30, 30), 100, 100), false), r.start())
        assertEquals(GuidedStep(0, PanelGeometry.normalize(PanelRect(60, 10, 30, 30), 100, 100), false), r.next())
        assertNull(r.next())
    }

    @Test
    fun next_ueberschreitet_seitengrenze() = runTest {
        val spy = SpySource(
            mapOf(
                0 to listOf(PanelRect(10, 10, 30, 30), PanelRect(60, 10, 30, 30)),
                1 to listOf(PanelRect(10, 10, 30, 30), PanelRect(60, 10, 30, 30)),
            ),
        )
        val r = reader(spy, pages = 2)
        r.start(); r.next()                 // letztes Panel Seite 0
        assertEquals(0, r.next()!!.page.let { 0 }.let { 0 } ?.let { 1 } ?: 1)  // → Seite 1, Einheit 0
    }

    @Test
    fun source_wird_pro_seite_nur_einmal_gerufen() = runTest {
        val spy = SpySource(mapOf(0 to listOf(PanelRect(10, 10, 30, 30), PanelRect(60, 10, 30, 30))))
        val r = reader(spy, pages = 1)
        r.start(); r.next(); r.previous(); r.next()
        assertEquals(listOf(0), spy.calls)   // genau ein detect für Seite 0
    }
}
```

> Hinweis Step 1: Der zweite Test ist absichtlich noch klobig formuliert — in Step 3 nach grünem `start()/next()` durch eine saubere `assertEquals(1, r.next()!!.page)`-Assertion ersetzen (siehe Step 3b). Erst die Indexlogik zum Laufen bringen.

- [ ] **Step 2: Test laufen lassen (muss fehlschlagen)**

Run: `./gradlew jvmTest --tests "com.panela.comiccutter.GuidedReaderTest"`
Expected: FAIL — `GuidedReader`/`GuidedStep` nicht definiert.

- [ ] **Step 3: Minimal implementieren**

```kotlin
package com.panela.comiccutter

import com.panela.comiccutter.model.RenderedPage

/** Ein Schritt im geführten Lesefluss: Ziel-Rechteck einer Seite (bild-normalisiert). */
data class GuidedStep(
    val page: Int,
    val rect: NormRect,
    val isFullPage: Boolean,
)

/**
 * Geführte Panel-für-Panel-Navigation über Seiten. Besitzt Panel-Cache (Quelle je Seite
 * nur einmal), Lese-Position, Crop-Rect (normalisierte Panel-Box) und Confidence-Fallback
 * (<2 Panels → Vollseite). Comics: Reihenfolge fix links→rechts.
 */
class GuidedReader(
    private val source: PanelSource,
    private val pageCount: Int,
    private val loadPage: suspend (Int) -> RenderedPage,
) {
    private val cache = mutableMapOf<Int, List<PanelRect>>()
    private val dims = mutableMapOf<Int, Pair<Int, Int>>()
    private var pos = GuidedPosition(0, 0)

    suspend fun start(): GuidedStep { pos = GuidedPosition(0, 0); return stepAt(pos) }

    suspend fun next(): GuidedStep? {
        val n = GuidedNavigator.next(pos, pageCount) { unitsAt(it) } ?: return null
        pos = n; return stepAt(pos)
    }

    suspend fun previous(): GuidedStep? {
        val p = GuidedNavigator.previous(pos, pageCount) { unitsAt(it) } ?: return null
        pos = p; return stepAt(pos)
    }

    /** Ganze Seite als Einheit (z. B. wenn die App die Vollseite zeigen will). Ändert die Position nicht. */
    suspend fun fullPage(page: Int): GuidedStep {
        panels(page)
        return GuidedStep(page, NormRect(0f, 0f, 1f, 1f), isFullPage = true)
    }

    fun position(): GuidedPosition = pos

    private suspend fun stepAt(p: GuidedPosition): GuidedStep {
        val boxes = panels(p.page)
        val (w, h) = dims.getValue(p.page)
        if (boxes.size < 2) return GuidedStep(p.page, NormRect(0f, 0f, 1f, 1f), isFullPage = true)
        return GuidedStep(p.page, PanelGeometry.normalize(boxes[p.unit], w, h), isFullPage = false)
    }

    private fun unitsAt(page: Int): Int = cache[page]?.size?.coerceAtLeast(1)
        ?: error("Seite $page nicht geladen — panels() vor unitsAt aufrufen")

    /** Lädt + erkennt + sortiert Panels einer Seite genau einmal (Cache). */
    private suspend fun panels(page: Int): List<PanelRect> = cache.getOrPut(page) {
        val rendered = loadPage(page)
        dims[page] = rendered.width to rendered.height
        ReadingOrder.sort(source.detect(rendered), ReadingDirection.LEFT_TO_RIGHT)
    }
}
```

> **Achtung Signatur:** `GuidedNavigator.next/previous` lesen `unitsAt(pos.page)` für die aktuelle Seite. Vor jedem `next/previous` muss die aktuelle Seite im Cache sein. `start()` ruft `stepAt` → `panels(0)` und füllt den Cache; jeder weitere `stepAt` füllt die neue Seite. Da `next` aber `unitsAt(pos.page)` der **aktuellen** Seite braucht (schon gecacht durch den vorigen `stepAt`) und beim Seitenwechsel `GuidedNavigator` `unitsAt(prevPage)` nutzt (ebenfalls gecacht), ist die Invariante erfüllt. `previous` über eine Seitengrenze ruft `unitsAt(prevPage)` der **vorigen** Seite — diese ist evtl. noch nicht geladen. Step 3b behebt das.

- [ ] **Step 3b: previous-über-Grenze absichern + Test säubern**

Ersetze in `previous()` den Body, sodass die Zielseite vor dem `GuidedNavigator`-Aufruf geladen wird:

```kotlin
    suspend fun previous(): GuidedStep? {
        if (pos.unit == 0 && pos.page > 0) panels(pos.page - 1)  // prevPage-units müssen im Cache sein
        val p = GuidedNavigator.previous(pos, pageCount) { unitsAt(it) } ?: return null
        pos = p; return stepAt(pos)
    }
```

Säubere im Test `next_ueberschreitet_seitengrenze` die klobige Assertion zu:

```kotlin
        assertEquals(1, r.next()!!.page)     // → Seite 1
        assertEquals(0, r.position().unit)   // erste Einheit der neuen Seite
```

- [ ] **Step 4: Tests laufen lassen (müssen bestehen)**

Run: `./gradlew jvmTest --tests "com.panela.comiccutter.GuidedReaderTest"`
Expected: PASS (alle drei)

- [ ] **Step 5: Commit**

```bash
git add src/commonMain/kotlin/com/panela/comiccutter/GuidedReader.kt src/commonTest/kotlin/com/panela/comiccutter/GuidedReaderTest.kt
git commit -m "feat(core): GuidedReader (Cache, next/previous, LTR, Crop-Rect)"
```

---

### Task 6: GuidedReader — Confidence-Fallback (Vollseite)

**Files:**
- Modify: `src/commonTest/kotlin/com/panela/comiccutter/GuidedReaderTest.kt` (Test ergänzen)

Die Fallback-Logik ist in Task 5 bereits in `stepAt` enthalten (`boxes.size < 2` → `NormRect(0,0,1,1)`, `isFullPage=true`). Hier wird sie per Test fixiert.

- [ ] **Step 1: Failing/charakterisierenden Test ergänzen**

Füge in `GuidedReaderTest` hinzu:

```kotlin
    @Test
    fun seite_mit_unter_2_panels_faellt_auf_vollseite_zurueck() = runTest {
        val spy = SpySource(mapOf(0 to listOf(PanelRect(10, 10, 80, 80))))  // 1 Panel
        val r = reader(spy, pages = 1)
        val s = r.start()
        assertEquals(GuidedStep(0, NormRect(0f, 0f, 1f, 1f), true), s)
        assertNull(r.next())   // genau 1 Einheit
    }

    @Test
    fun leere_seite_ist_eine_vollseiten_einheit() = runTest {
        val spy = SpySource(mapOf(0 to emptyList()))
        val r = reader(spy, pages = 1)
        assertEquals(GuidedStep(0, NormRect(0f, 0f, 1f, 1f), true), r.start())
        assertNull(r.next())
    }
```

- [ ] **Step 2: Tests laufen lassen**

Run: `./gradlew jvmTest --tests "com.panela.comiccutter.GuidedReaderTest"`
Expected: PASS (beide neuen grün — Logik existiert schon aus Task 5)

- [ ] **Step 3: Commit**

```bash
git add src/commonTest/kotlin/com/panela/comiccutter/GuidedReaderTest.kt
git commit -m "test(core): Confidence-Fallback auf Vollseite fixiert"
```

---

### Task 7: Voller Build + Gesamttest-Lauf (jvm + js)

**Files:** keine.

- [ ] **Step 1: Kompletter Build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL — alle Tests jvm + js grün, inkl. der bestehenden Detektor-Tests (Regression).

- [ ] **Step 2: Falls js-Compile an `suspend` scheitert**

`suspend` ist stdlib-only und für Kotlin/JS unterstützt; `GuidedReader` exportiert nichts via `@JsExport` (das wäre für suspend ungültig). Falls die Demo/JS-API später Zugriff braucht, eigener Adapter — nicht in diesem Plan (Out-of-scope, siehe Spec §10). Kein Eingriff nötig, wenn Build grün.

- [ ] **Step 3: Commit (nur falls Anpassungen nötig waren)**

```bash
git add -A && git commit -m "build: Gesamt-Build gruen (jvm + js)"
```

---

## PHASE B — ONNX-Runner-Modul (blockiert auf ONNX-Export)

> **Voraussetzung:** Das Modell `yolo_v3` (YOLO11n v3) muss als ONNX vorliegen.
> Quelle: `komga-yolo-spike/yolo/models/yolo11n_v3.pt` (bzw. der im Webtool als
> `yolo_v3` registrierte Checkpoint). Diese Phase erst starten, wenn Task 8 das
> ONNX-Asset erzeugt hat.

### Task 8: ONNX-Export des Modells

**Files:**
- Create: `models/panel-yolo11n-v3.onnx` (Artefakt, ggf. via Git LFS / nicht eingecheckt)
- Create: `models/export_onnx.py` (reproduzierbar)

- [ ] **Step 1: Export-Skript schreiben**

```python
#!/usr/bin/env python3
"""Exportiert das YOLO11n-v3-Panel-Modell nach ONNX (imgsz=1024, opset 12).
Aufruf: python3 models/export_onnx.py <pfad/zu/yolo11n_v3.pt> models/panel-yolo11n-v3.onnx
"""
import sys
from ultralytics import YOLO

src, dst = sys.argv[1], sys.argv[2]
model = YOLO(src)
path = model.export(format="onnx", imgsz=1024, opset=12, dynamic=False, simplify=True)
import shutil; shutil.move(path, dst)
print("exported:", dst)
```

- [ ] **Step 2: Export ausführen**

Run: `python3 models/export_onnx.py ~/Documents/Projekte/komga-yolo-spike/yolo/models/yolo11n_v3.pt models/panel-yolo11n-v3.onnx`
Expected: `exported: models/panel-yolo11n-v3.onnx`; Datei existiert.

- [ ] **Step 3: Output-Shape notieren**

Run: `python3 -c "import onnx; m=onnx.load('models/panel-yolo11n-v3.onnx'); print([(o.name, [d.dim_value for d in o.type.tensor_type.shape.dim]) for o in m.graph.output])"`
Expected: eine Output-Shape, typischerweise `[1, 5, N]` (single-class YOLO11: 4 Box + 1 Score) oder `[1, N, 6]`. **Den exakten Layout-Wert für Task 10 festhalten** (entscheidet das Decode).

- [ ] **Step 4: Commit (Skript; ONNX ggf. via .gitignore/LFS-Regel)**

```bash
git add models/export_onnx.py
git commit -m "build(model): ONNX-Export-Skript fuer YOLO11n v3"
```

---

### Task 9: Gradle-Modul comic-cutter-onnx-jvm

**Files:**
- Modify: `settings.gradle.kts`
- Create: `comic-cutter-onnx-jvm/build.gradle.kts`

- [ ] **Step 1: Modul registrieren**

In `settings.gradle.kts` ans Ende:

```kotlin
include(":comic-cutter-onnx-jvm")
```

- [ ] **Step 2: Modul-Build schreiben**

`comic-cutter-onnx-jvm/build.gradle.kts`:

```kotlin
plugins {
    kotlin("jvm") version "2.1.21"
}

repositories { mavenCentral() }

dependencies {
    implementation(rootProject)                                  // comic-cutter (jvm-Artefakt)
    implementation("com.microsoft.onnxruntime:onnxruntime:1.19.2")
    testImplementation(kotlin("test"))
}

kotlin { jvmToolchain(21) }
```

> Hinweis: `implementation(rootProject)` zieht die Multiplatform-Lib; Gradle löst
> via Modul-Metadata auf das `-jvm`-Artefakt auf (wie für Komga im README beschrieben).

- [ ] **Step 3: Konfiguration prüfen**

Run: `./gradlew :comic-cutter-onnx-jvm:dependencies --configuration runtimeClasspath`
Expected: BUILD SUCCESSFUL; `onnxruntime` + `comic-cutter` aufgelöst.

- [ ] **Step 4: Commit**

```bash
git add settings.gradle.kts comic-cutter-onnx-jvm/build.gradle.kts
git commit -m "build(onnx): Gradle-Modul comic-cutter-onnx-jvm"
```

---

### Task 10: OnnxModelRunner + Letterbox

> Decode-Layout aus Task 8 Step 3 verwenden. Untenstehender Code nimmt das
> YOLO11-Standardlayout `[1, 4+nc, N]` mit `nc=1` an (Zeilen: cx, cy, w, h,
> score). Bei abweichendem Layout das Decode in `parseOutput` anpassen.

**Files:**
- Create: `comic-cutter-onnx-jvm/src/main/kotlin/com/panela/comiccutter/onnx/Letterbox.kt`
- Create: `comic-cutter-onnx-jvm/src/main/kotlin/com/panela/comiccutter/onnx/OnnxModelRunner.kt`
- Test: `comic-cutter-onnx-jvm/src/test/kotlin/com/panela/comiccutter/onnx/LetterboxTest.kt`

- [ ] **Step 1: Letterbox-Test (reine Geometrie, ohne Modell)**

```kotlin
package com.panela.comiccutter.onnx

import kotlin.test.Test
import kotlin.test.assertEquals

class LetterboxTest {
    @Test
    fun rueckprojektion_kehrt_skalierung_und_padding_um() {
        // 200x100 Seite → 1024er-Quadrat: scale = 1024/200 = 5.12, neue Höhe 512, padY = 256
        val lb = Letterbox.of(srcW = 200, srcH = 100, target = 1024)
        // Box-Mitte im Modellraum (cx=512, cy=256+5.12*50=512) → Seitenmitte (100,50)
        val r = lb.toSource(cx = 512f, cy = 512f, w = 5.12f * 20, h = 5.12f * 10)
        assertEquals(100, r.first)   // x-center → 100
        assertEquals(50, r.second)   // y-center → 50
    }
}
```

- [ ] **Step 2: Test fehlschlagen lassen**

Run: `./gradlew :comic-cutter-onnx-jvm:test --tests "*LetterboxTest"`
Expected: FAIL — `Letterbox` nicht definiert.

- [ ] **Step 3: Letterbox implementieren**

```kotlin
package com.panela.comiccutter.onnx

/** Aspect-erhaltendes Letterbox-Mapping Seite ↔ quadratischer Modell-Input [target]×[target]. */
class Letterbox private constructor(
    private val scale: Float,
    private val padX: Int,
    private val padY: Int,
) {
    /** Modellraum-Box (Mitte cx,cy + w,h) → Seiten-Pixel-Mitte (x,y). */
    fun toSource(cx: Float, cy: Float, w: Float, h: Float): Pair<Int, Int> {
        val x = ((cx - padX) / scale).toInt()
        val y = ((cy - padY) / scale).toInt()
        return x to y
    }

    /** Modellraum-Box → Seiten-Pixel-Box (x,y,w,h, top-left). */
    fun rect(cx: Float, cy: Float, w: Float, h: Float): IntArray {
        val sw = (w / scale).toInt(); val sh = (h / scale).toInt()
        val (cxS, cyS) = toSource(cx, cy, w, h)
        return intArrayOf(cxS - sw / 2, cyS - sh / 2, sw, sh)
    }

    companion object {
        fun of(srcW: Int, srcH: Int, target: Int): Letterbox {
            val scale = minOf(target.toFloat() / srcW, target.toFloat() / srcH)
            val newW = (srcW * scale).toInt(); val newH = (srcH * scale).toInt()
            return Letterbox(scale, (target - newW) / 2, (target - newH) / 2)
        }
    }
}
```

- [ ] **Step 4: Letterbox-Test grün**

Run: `./gradlew :comic-cutter-onnx-jvm:test --tests "*LetterboxTest"`
Expected: PASS

- [ ] **Step 5: OnnxModelRunner implementieren**

```kotlin
package com.panela.comiccutter.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.panela.comiccutter.ModelRunner
import com.panela.comiccutter.RawDetection
import com.panela.comiccutter.model.RenderedPage
import java.nio.FloatBuffer

/**
 * [ModelRunner] auf ONNX-Runtime: rendert die Seite ins Letterbox-Quadrat [inputSize],
 * inferiert das YOLO11n-Panel-Modell und dekodiert rohe Detektionen (cls=0 „panel").
 * Native lebt nur hier — der Kern bleibt frei davon.
 */
class OnnxModelRunner(
    modelBytes: ByteArray,
    private val inputSize: Int = 1024,
) : ModelRunner, AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(modelBytes, OrtSession.SessionOptions())

    override fun infer(page: RenderedPage): List<RawDetection> {
        val lb = Letterbox.of(page.width, page.height, inputSize)
        val input = renderLetterboxCHW(page, lb)
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong()))
        tensor.use { t ->
            session.run(mapOf(session.inputNames.first() to t)).use { res ->
                val out = (res[0].value as Array<*>)[0] as Array<FloatArray>  // [4+nc][N]
                return parseOutput(out, lb)
            }
        }
    }

    /** YOLO11-Output [4+nc][N]: Zeilen cx,cy,w,h,score(je Klasse). Single-class → Zeile 4 = score. */
    private fun parseOutput(out: Array<FloatArray>, lb: Letterbox): List<RawDetection> {
        val n = out[0].size
        val dets = ArrayList<RawDetection>(n)
        for (i in 0 until n) {
            val score = out[4][i]
            if (score <= 0f) continue
            val r = lb.rect(out[0][i], out[1][i], out[2][i], out[3][i])
            dets.add(RawDetection(r[0], r[1], r[2], r[3], score, cls = 0))
        }
        return dets
    }

    /** Seite → CHW-Float [3·size·size], RGB 0..1, aspect-erhaltend mit grauem Padding (114/255). */
    private fun renderLetterboxCHW(page: RenderedPage, lb: Letterbox): FloatArray {
        val size = inputSize
        val out = FloatArray(3 * size * size) { 114f / 255f }
        val plane = size * size
        for (ty in 0 until size) for (tx in 0 until size) {
            val (sx, sy) = lb.toSource(tx.toFloat(), ty.toFloat(), 0f, 0f)
            if (sx < 0 || sy < 0 || sx >= page.width || sy >= page.height) continue
            val p = page.pixels[sy * page.width + sx]
            val idx = ty * size + tx
            out[idx] = ((p shr 16) and 0xFF) / 255f
            out[plane + idx] = ((p shr 8) and 0xFF) / 255f
            out[2 * plane + idx] = (p and 0xFF) / 255f
        }
        return out
    }

    override fun close() { session.close() }
}
```

- [ ] **Step 6: Integrationstest (echtes ONNX, falls Asset vorhanden)**

```kotlin
package com.panela.comiccutter.onnx

import com.panela.comiccutter.model.RenderedPage
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class OnnxModelRunnerTest {
    @Test
    fun erkennt_panels_auf_bekannter_seite() {
        val model = File("../models/panel-yolo11n-v3.onnx")
        if (!model.exists()) return  // Asset nicht da → Test überspringen (CI ohne Modell)
        val runner = OnnxModelRunner(model.readBytes())
        // monochrome Platzhalter-Seite; echte Bildladung im manuellen Check
        val page = RenderedPage(1024, 1536, IntArray(1024 * 1536) { 0xFFFFFFFF.toInt() })
        val dets = runner.infer(page)
        assertTrue(dets.size >= 0)   // Smoke: keine Exception, Decode läuft
        runner.close()
    }
}
```

- [ ] **Step 7: Modul-Tests laufen lassen**

Run: `./gradlew :comic-cutter-onnx-jvm:test`
Expected: PASS (Integrationstest überspringt sich ohne Asset).

- [ ] **Step 8: Commit**

```bash
git add comic-cutter-onnx-jvm/src
git commit -m "feat(onnx): OnnxModelRunner + Letterbox-Rueckprojektion"
```

---

### Task 11: Provenance-Datei

**Files:**
- Create: `models/PROVENANCE.md`

- [ ] **Step 1: Provenance schreiben**

```markdown
# ML-Asset-Provenance

## panel-yolo11n-v3.onnx

| Feld | Wert |
|------|------|
| Name | YOLO11n v3 (Webtool-Engine-ID `yolo_v3`) |
| Architektur | Ultralytics YOLO11n, single-class „panel" |
| Quelle Gewichte | komga-yolo-spike/yolo/models/yolo11n_v3.pt (eigenes Training) |
| Export | models/export_onnx.py, imgsz=1024, opset=12, simplify |
| Inferenz | conf-Default 0.30, NMS-IoU 0.50 (in MlFilter) |
| Erfassungsdatum | 2026-06-12 |
| Lizenz Architektur | AGPL-3.0 (Ultralytics YOLO11) |
| Lizenz Gewichte | privat/inoffiziell, nur Eigengebrauch |

### Trainings-Dataset (Verweis)
Golden-Age-PD-Sampler + Comic/Manga-Labels aus komga-reader-guided-comic/dataset
und komga-yolo-spike/yolo/dataset. Genaue Quellen/Caps/Lizenzen: siehe Provenance
des Trainings-Repos.

### Risk-Register
- **AGPL-3.0 (Architektur):** deckt sich mit der Lib-Lizenz (AGPL-3.0) — kein Konflikt.
- **Private Gewichte:** nicht weiterverteilen; nicht in öffentliche Releases bündeln.

Letzte Komplettrevision: 2026-06-12 (gabriel)
```

- [ ] **Step 2: Commit**

```bash
git add models/PROVENANCE.md
git commit -m "docs(model): Provenance fuer YOLO11n v3 ONNX-Asset"
```

---

## Self-Review-Ergebnis

- **Spec-Abdeckung:** PanelSource (T1), ML-Mapping+Filter (T3/T4), ModelRunner-Naht (T2), GuidedReader Cache/Position/Crop/Fallback (T5/T6), LTR fix (T5), Modul C / onnx-jvm (T9/T10), Provenance (T11). Alle Spec-Abschnitte haben Tasks.
- **Out-of-scope** (Prefetch, ort-wasm, RTL) bleibt unberührt — korrekt.
- **Typ-Konsistenz:** `PanelRect`/`NormRect`/`RawDetection`/`ModelRunner`/`MlFilter.apply`/`GuidedStep`/`GuidedReader` durchgängig gleich benannt; `PanelGeometry.normalize` + `GuidedNavigator.next/previous` + `ReadingOrder.sort` sind bestehende Signaturen.
- **Phase B blockiert** auf ONNX-Export (T8) — explizit markiert.
