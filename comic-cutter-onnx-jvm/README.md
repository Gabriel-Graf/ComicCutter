# comic-cutter-onnx-jvm

Optionales JVM-Modul: ein `ModelRunner` auf Basis von **ONNX-Runtime**, der ein
YOLO11 single-class Panel-Modell on-edge ausführt. Steckt hinter der
`PanelSource`-Naht des Kerns (`comic-cutter`) — der Kern selbst bleibt
rein-Kotlin und native-frei.

## Das Modell ist NICHT Teil dieses Repos

`OnnxModelRunner(modelBytes)` bekommt die Modell-Bytes vom Konsumenten zur
Laufzeit. Es wird kein `.onnx` mitgeliefert (`*.onnx` ist gitignored). Provenance
und Gewichte des konkreten Modells liegen beim Modell, nicht hier.

## Modell-Kontrakt

Der Runner erwartet ein Modell mit genau diesem Format (YOLO11-Export):

| | |
|---|---|
| **Input** | `(1, 3, 1024, 1024)` — RGB, `/255`, NCHW, aspect-erhaltendes Letterbox (Pad-Farbe 114) |
| **Output** | `(1, 4+nc, N)` mit `nc=1` → `(1, 5, 8400)`: Zeilen `cx, cy, w, h, score` |
| **Klasse** | single-class `panel` (cls=0) |

Score-Threshold und NMS macht der Runner **nicht** — er liefert rohe
`RawDetection` in Seiten-Pixel-Koordinaten. conf/NMS/min-area bestimmt der
`MlFilter`:

```kotlin
val runner = OnnxModelRunner(modelBytes)                 // Konsument reicht die Bytes
val source = MlPanelSource(runner, MlFilter(minScore = 0.25f, nmsIoU = 0.7f))
val guide  = PanelGuide(source).guide(renderedPage)      // → geordnete Crop-Rects
```

Die Defaults `conf 0.25` / `NMS-IoU 0.7` entsprechen der Trainings-Referenz des
Panel-Detektors. Eine UI darf strenger filtern (höheres `minScore`).

## CLI: Modell offline gegen ein Bild laufen lassen

Das Modul ist auch ausführbar — zum schnellen Testen der Lib **ohne Python**,
mit einem lokalen, einem Hugging-Face- oder einem beliebigen HTTP-Modell:

```bash
# Lokales Modell
./gradlew :comic-cutter-onnx-jvm:run --args="\
  --model /pfad/zu/best.onnx --image /pfad/zu/seite.jpg --overlay out.png"

# Hugging Face (wird einmalig nach ~/.cache/comic-cutter-onnx/ geladen, danach offline)
./gradlew :comic-cutter-onnx-jvm:run --args="\
  --model hf:org/repo/best.onnx --image seite.jpg"
```

| Flag | Default | Bedeutung |
|---|---|---|
| `--model` | — | Lokaler Pfad \| `hf:org/repo[@rev]/datei.onnx` \| `https://…` |
| `--image` | — | Eingabe-Seite (jpg/png/…) |
| `--adapter` | `yolo11` | Output-Adapter (siehe unten) |
| `--imgsz` | `1024` | Letterbox-Kantenlänge |
| `--conf` / `--nms` | `0.25` / `0.7` | Score-Schwelle / NMS-IoU |
| `--rtl` | aus | Manga-Lesereihenfolge (rechts→links) |
| `--overlay` | — | Overlay-PNG mit nummerierten Boxen schreiben |

Ausgabe: Panels als JSON-Array `[{order,x,y,width,height}, …]` (Seiten-Pixel) nach stdout.

## Eigener Adapter für ein abweichendes Modell

Der `OnnxModelRunner` macht nur das Session-Plumbing; alles Modell-Spezifische
(Input-Geometrie, Preprocessing, Output-Decode) liegt im `OnnxAdapter`. Der
Festkontrakt oben ist der mitgelieferte `Yolo11Adapter` — der Default. Bringt
ein Community-Modell ein anderes Output-Layout mit (xyxy, transponiert, mit
Objectness, NHWC, BGR …), wird **kein Python** nötig: ein eigener `OnnxAdapter`
in Kotlin genügt, danach über die `AdapterRegistry` namentlich nutzbar.

```kotlin
class MyAdapter(override val inputSize: Int = 640) : OnnxAdapter {
    override val inputShape = longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
    override fun preprocess(page: RenderedPage, lb: Letterbox): FloatArray = /* … */
    override fun decode(result: OrtSession.Result, lb: Letterbox): List<RawDetection> = /* … */
}

AdapterRegistry.register("my-model") { size -> MyAdapter(size) }   // dann: --adapter my-model
val runner = OnnxModelRunner(modelBytes, AdapterRegistry.create("my-model"))
```

## Test

`OnnxModelRunnerIntegrationTest` ist ein lokaler Smoke gegen ein echtes Modell.
Modellpfad via Env `PANEL_ONNX_MODEL`, sonst ein lokaler Default. Fehlt das
Modell oder das Testbild (`demo/dist/` ist gitignored), überspringt sich der Test
— CI ohne Modell bleibt grün.

```bash
PANEL_ONNX_MODEL=/pfad/zu/best.int8.onnx ./gradlew :comic-cutter-onnx-jvm:test
```
