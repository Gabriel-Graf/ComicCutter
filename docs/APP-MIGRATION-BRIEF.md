# Migration brief: switching komga-reader to the ComicCutter library

**Goal:** Replace the app's local guided-view heuristic with the **ComicCutter**
library — the app is decoupled from the heuristic. For "next_panel" the app now
only calls `PanelGuide.guide(page)` and gets the tiles in display order.

**Core rule:** **No heuristic code is copied into the app.** The app takes a
dependency on the library and **deletes** its own copy. One source of truth. The
library's heuristic is newer/stronger than the app's state (it additionally has
`GutterProfileCut` as the primary path, a merge pass, downscale, flood
arbitration) — so detection gets better with the switch, not just equal.

---

## 1. Add the dependency

**Now (local, immediately buildable):** The library is published as `0.3.2` in MavenLocal.

```kotlin
// settings.gradle.kts / build.gradle.kts of the app
repositories {
    mavenLocal()
    mavenCentral()
}
dependencies {
    implementation("io.github.gabriel-graf:comic-cutter:0.3.2")
    // Gradle resolves automatically to the -jvm artifact via KMP metadata.
}
```

**Later (real release, CI):** Once a tag `0.3.2` is pushed to GitHub:

```kotlin
repositories { maven("https://jitpack.io") }
dependencies { implementation("com.github.Gabriel-Graf:ComicCutter:0.3.2") }
```

> The optional ML module `comic-cutter-onnx-jvm` (ONNX runner) is **not yet**
> needed — "next_panel" runs over the geometric detector (default). ML is a
> later additive step.

---

## 2. What the app CALLS (the library's public API)

Everything in the package **`com.panela.comiccutter`** (no longer `com.komgareader.comiccutter`):

```kotlin
import com.panela.comiccutter.PanelGuide
import com.panela.comiccutter.PageGuide
import com.panela.comiccutter.NormRect
import com.panela.comiccutter.PanelGeometry
import com.panela.comiccutter.model.RenderedPage

// ONE image in → tiles in display order out (stateless, synchronous):
val guide: PageGuide = PanelGuide().guide(renderedPage)
//   guide.steps      : List<NormRect>  — ordered LTR, normalized 0..1
//   guide.isFullPage : Boolean         — true = <2 tiles → steps == [NormRect(0,0,1,1)]
```

`PanelGuide()` uses the geometric detector by default
(`GeometricPanelSource()`). No parameter needed.

Still usable for rendering (unchanged from the old app geometry):
`PanelGeometry.fitScale(...)`, `PanelGeometry.normalize(...)`,
`PanelGeometry.hitTest(...)`, type `NormRect(left, top, width, height: Float)`.

---

## 3. RenderedPage adapter

The library has its own `RenderedPage` type (`com.panela.comiccutter.model`),
the same shape as the app's type. The app still renders the page itself to
ARGB pixels and builds the library type from it:

```kotlin
fun com.komgareader.domain.render.RenderedPage.toLib() =
    com.panela.comiccutter.model.RenderedPage(width, height, pixels)
// pixels: raw ARGB_8888, row-major (i = y*width + x) — identical layout.
```

---

## 4. "next_panel" — the reader loop (stays in the app)

**Page management stays entirely with the reader**: which page, page jumps,
progress, remembering/restoring position. The library does NOT navigate across
page boundaries.

```kotlin
// The reader holds currentPage + unit itself:
val g = panelGuide.guide(currentPage.toLib())   // once per page; reader caches the result
val target: NormRect = g.steps[unit]            // "next_panel" = unit++ in g.steps
//   unit beyond g.steps       → reader loads the next page, calls guide() again, unit=0
//   unit < 0                  → reader goes back one page, unit = lastSteps.lastIndex
showStep(target)                                 // target into PanelGeometry.fitScale(...) → "pan there"
//   g.isFullPage == true     → show the whole page (fallback for <2 tiles)
```

`guide()` is blocking (pure CPU heuristic) → call it on a background
dispatcher. Cache the result per page (the reader owns the pages).

---

## 5. What gets DELETED

The app's local heuristic copy — **remove it completely**, it is now the
library:

- `com/komgareader/comiccutter/PanelDetector.kt`
- `com/komgareader/comiccutter/ImageBinarization.kt`
- `com/komgareader/comiccutter/GutterFill.kt`
- `com/komgareader/comiccutter/RegionLabeling.kt`
- `com/komgareader/comiccutter/BorderLineSplit.kt`
- `com/komgareader/comiccutter/ReadingOrder.kt`
- `com/komgareader/comiccutter/PanelGeometry.kt`
- `com/komgareader/comiccutter/Panel.kt`
- die alte Guided-View-Logik in `domain` (Reihenfolge/Crop/Fallback), soweit sie
  durch `PanelGuide` ersetzt wird

…along with their tests, if those only cover the deleted copy (the behavioral
safety net now lives in the library).

## 6. What STAYS in the app

- Loading/rendering pages → ARGB (the `RenderedPage` source).
- Paging, reading position, progress, position persistence/restore.
- Viewport rendering / zoom (may keep using `PanelGeometry.fitScale`).
- The app's `RenderedPage` type stays; only the `.toLib()` adapter is added.

---

## 7. Notes

- **Reading direction:** `PanelGuide` is **fixed left→right** (comics). If the
  app does need RTL/manga, don't use `PanelGuide` but directly
  `ReadingOrder.sort(GeometricPanelSource().detect(page), ReadingDirection.RIGHT_TO_LEFT)`
  + `PanelGeometry.normalize`. (Per scope: comics only → `PanelGuide` suffices.)
- **Detection changes slightly:** The library's heuristic is better than the old
  app copy — panel boxes may come out minimally different (usually more correct).
  Not a bug.
- **Verification:** The app builds green; on a known multi-panel page,
  `guide(page).steps.size >= 2` and `isFullPage == false`.

---

## 8. ML later (not now)

If a YOLO panel model is to be added without changing the app's calls: just swap
the `source` in `PanelGuide` —
`PanelGuide(MlPanelSource(runner, MlFilter(minScore = 0.25f, nmsIoU = 0.7f)))`.
The runner is provided by the optional, separately published module:

```kotlin
implementation("io.github.gabriel-graf:comic-cutter-onnx-jvm:0.3.2")  // pulls in onnxruntime + core
```

```kotlin
val runner = OnnxModelRunner(modelBytes)   // the reader passes modelBytes in at runtime
```

The model (YOLO11 `(1,5,8400)`, INT8-ONNX) is NOT part of the library. The
`guide()` calls in the app stay identical. Expected output format and
letterbox details: `comic-cutter-onnx-jvm/README.md`. (Parity-tested against
the Python reference, IoU ≥ 0.95.)
