# GuidedComic — Comic-Cutter

Pure-Kotlin detector for comic/manga **panels**: it detects the tiles of a
rendered page, sorts them into reading order, and provides guided
panel-to-panel navigation. **Host-independent** — no AWT, no `ImageIO`,
no third-party dependency beyond the Kotlin stdlib. It therefore runs headless
(e.g. inside a server like Komga) — and thanks to Kotlin/JS, even directly in the browser.

## Live demo

Panel detection directly in the browser, no backend:
**https://gabriel-graf.github.io/ComicCutter/**

Load public-domain example pages (source/license visibly linked) or upload your own
image — detection runs entirely locally, nothing is uploaded.

## Approach

The primary path is a color-agnostic **profile XY-cut** (`GutterProfileCut`)
that separates light *and* dark gutters via projection statistics and also
tolerates gutters bridged by speech balloons. If it finds no gutter (full-bleed
splash), a **flood-fill fallback** kicks in, working over the white-gutter mesh
connected to the page border. A merge pass fuses tiles fragmented across internal
edges based on content continuity.

## Integration

> Requires JVM 21+. As of **0.2.0** the library is Kotlin Multiplatform (jvm + js).
> Gradle consumers (e.g. Komga) use the coordinate below unchanged —
> Gradle resolves automatically to the `-jvm` artifact via module metadata.

### Option A — JitPack (a tag is enough, no server infrastructure)

```kotlin
repositories {
    mavenCentral()
    maven("https://jitpack.io")
}
dependencies {
    implementation("com.github.Gabriel-Graf:ComicCutter:0.2.2")
}
```

### Option B — Maven Central (once published)

```kotlin
dependencies {
    implementation("io.github.gabriel-graf:comic-cutter:0.2.2")
}
```

## Usage

```kotlin
import com.panela.comiccutter.PanelDetector
import com.panela.comiccutter.ReadingDirection
import com.panela.comiccutter.model.RenderedPage

// Page as ARGB pixels (fill from BufferedImage or similar on the host side):
//   val px = IntArray(w * h); img.getRGB(0, 0, w, h, px, 0, w)
val page = RenderedPage(width, height, px)

val panels = PanelDetector().detect(page, ReadingDirection.RIGHT_TO_LEFT)
// → List<PanelRect> in reading order
```

For guided navigation see `GuidedNavigator` / `GuidedPosition`.

## Public API

| Type | Purpose |
|-----|-------|
| `PanelDetector` | Panel detection: `detect(page, direction): List<PanelRect>` |
| `RenderedPage` | Host-independent page: `(width, height, IntArray /*ARGB*/)` |
| `ReadingDirection` | `LEFT_TO_RIGHT` / `RIGHT_TO_LEFT` |
| `PanelRect` / `NormRect` | Panel bounding box (pixel or normalized) |
| `PanelSource` | Seam: tiles from the detector (`GeometricPanelSource`) **or** ML (`MlPanelSource`) |
| `PanelGuide` | `guide(page): PageGuide` — image → ordered crop rects (+ full-page fallback) |
| `GuidedNavigator` | Panel-to-panel index logic across pages (cross-page; usable by the reader) |

### ML source (optional)

The `PanelSource` seam allows an ML model instead of, or alongside, the
geometric detector. The core only defines `ModelRunner` (interface) +
`MlPanelSource` + `MlFilter` (conf/NMS/min-area, pure). A ready-made ONNX runner
is provided by the optional JVM module **`comic-cutter-onnx-jvm`**
(`OnnxModelRunner`, ONNX-Runtime) — the **model itself is not part of the
library**; the consumer passes it in at runtime. Details:
[`comic-cutter-onnx-jvm/README.md`](comic-cutter-onnx-jvm/README.md).

#### Bring your own model

The model-specific parts (input geometry, preprocessing, output decode) live in
the `OnnxAdapter`; `OnnxModelRunner` only does the session plumbing. Shipped is
`Yolo11Adapter` (fixed contract YOLO11 single-class) as the default. A community
model with a different output (xyxy, transposed, NHWC, BGR …) needs **no Python** —
a custom `OnnxAdapter` in Kotlin is enough, registrable via the `AdapterRegistry`.

#### CLI: test a model against an image offline

The module is executable — a quick test of the library with your own or a
third-party model, without Python:

```bash
# Local model + overlay PNG
./gradlew :comic-cutter-onnx-jvm:run -q --args="\
  --model /path/best.onnx --image /path/page.jpg --overlay out.png"

# Hugging Face (one-time download to ~/.cache/comic-cutter-onnx/, offline afterwards)
./gradlew :comic-cutter-onnx-jvm:run -q --args="\
  --model hf:org/repo/best.onnx --image /path/page.jpg"
```

`--model` takes a local path, `hf:org/repo[@rev]/file.onnx`, or `https://…`;
further flags: `--adapter --imgsz --conf --nms --rtl --overlay`. Output: panels
as JSON to stdout. (Gradle's `run` cwd is the module folder → use absolute paths.)

## Building

```bash
./gradlew build               # compile + tests (jvm + js)
./gradlew publishToMavenLocal # to ~/.m2 (local integration)
./gradlew jsBrowserDistribution # JS bundle for the demo
```

Demo locally: copy the bundle to `demo/src/kotlin/`, then `cd demo && npm install && npm run dev`.
Deploy to GitHub Pages runs automatically via `.github/workflows/pages.yml`.

## License

[AGPL-3.0](LICENSE). Consuming projects are therefore subject to the AGPL copyleft
(incl. the network clause).
