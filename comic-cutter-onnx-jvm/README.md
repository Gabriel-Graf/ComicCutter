# comic-cutter-onnx-jvm

Optional JVM module: a `ModelRunner` based on **ONNX-Runtime** that runs a
YOLO11 single-class panel model on-edge. It sits behind the core's
`PanelSource` seam (`comic-cutter`) — the core itself stays pure-Kotlin and
native-free.

## The model is NOT part of this repo

`OnnxModelRunner(modelBytes)` receives the model bytes from the consumer at
runtime. No `.onnx` is shipped (`*.onnx` is gitignored). Provenance and weights
of the concrete model belong with the model, not here.

## Model contract

The runner expects a model in exactly this format (YOLO11 export):

| | |
|---|---|
| **Input** | `(1, 3, 1024, 1024)` — RGB, `/255`, NCHW, aspect-preserving letterbox (pad color 114) |
| **Output** | `(1, 4+nc, N)` with `nc=1` → `(1, 5, 8400)`: rows `cx, cy, w, h, score` |
| **Class** | single-class `panel` (cls=0) |

The runner does **not** apply the score threshold or NMS — it returns raw
`RawDetection` in page-pixel coordinates. conf/NMS/min-area are determined by the
`MlFilter`:

```kotlin
val runner = OnnxModelRunner(modelBytes)                 // consumer passes the bytes
val source = MlPanelSource(runner, MlFilter(minScore = 0.25f, nmsIoU = 0.7f))
val guide  = PanelGuide(source).guide(renderedPage)      // → ordered crop rects
```

The defaults `conf 0.25` / `NMS-IoU 0.7` match the training reference of the
panel detector. A UI may filter more strictly (higher `minScore`).

## CLI: run a model against an image offline

The module is also executable — to quickly test the library **without Python**,
with a local, a Hugging Face, or any HTTP model:

```bash
# Local model
./gradlew :comic-cutter-onnx-jvm:run --args="\
  --model /path/to/best.onnx --image /path/to/page.jpg --overlay out.png"

# Hugging Face (downloaded once to ~/.cache/comic-cutter-onnx/, offline afterwards)
./gradlew :comic-cutter-onnx-jvm:run --args="\
  --model hf:org/repo/best.onnx --image page.jpg"
```

| Flag | Default | Meaning |
|---|---|---|
| `--model` | — | Local path \| `hf:org/repo[@rev]/file.onnx` \| `https://…` |
| `--image` | — | Input page (jpg/png/…) |
| `--adapter` | `yolo11` | Output adapter (see below) |
| `--imgsz` | `1024` | Letterbox edge length |
| `--conf` / `--nms` | `0.25` / `0.7` | Score threshold / NMS-IoU |
| `--rtl` | off | Manga reading order (right→left) |
| `--overlay` | — | Write an overlay PNG with numbered boxes |

Output: panels as a JSON array `[{order,x,y,width,height}, …]` (page pixels) to stdout.

## Custom adapter for a different model

The `OnnxModelRunner` only does the session plumbing; everything model-specific
(input geometry, preprocessing, output decode) lives in the `OnnxAdapter`. The
fixed contract above is the shipped `Yolo11Adapter` — the default. If a community
model comes with a different output layout (xyxy, transposed, with objectness,
NHWC, BGR …), **no Python** is needed: a custom `OnnxAdapter` in Kotlin is
enough, then usable by name via the `AdapterRegistry`.

```kotlin
class MyAdapter(override val inputSize: Int = 640) : OnnxAdapter {
    override val inputShape = longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
    override fun preprocess(page: RenderedPage, lb: Letterbox): FloatArray = /* … */
    override fun decode(result: OrtSession.Result, lb: Letterbox): List<RawDetection> = /* … */
}

AdapterRegistry.register("my-model") { size -> MyAdapter(size) }   // then: --adapter my-model
val runner = OnnxModelRunner(modelBytes, AdapterRegistry.create("my-model"))
```

## Test

`OnnxModelRunnerIntegrationTest` is a local smoke test against a real model.
The model path comes from the env var `PANEL_ONNX_MODEL`, otherwise a local
default. If the model or the test image is missing (`demo/dist/` is gitignored),
the test skips itself — CI without a model stays green.

```bash
PANEL_ONNX_MODEL=/path/to/best.int8.onnx ./gradlew :comic-cutter-onnx-jvm:test
```
