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

## Test

`OnnxModelRunnerIntegrationTest` ist ein lokaler Smoke gegen ein echtes Modell.
Modellpfad via Env `PANEL_ONNX_MODEL`, sonst ein lokaler Default. Fehlt das
Modell oder das Testbild (`demo/dist/` ist gitignored), überspringt sich der Test
— CI ohne Modell bleibt grün.

```bash
PANEL_ONNX_MODEL=/pfad/zu/best.int8.onnx ./gradlew :comic-cutter-onnx-jvm:test
```
