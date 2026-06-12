# GuidedComic als entkoppelte Guided-Reading-Engine

**Datum:** 2026-06-12
**Status:** Design abgenommen, bereit für Plan

## 1. Kontext & Ziel

`GuidedComic` ist heute eine reine-Kotlin-Multiplatform-Lib (jvm + js), die
Comic-Panels geometrisch erkennt (`PanelDetector`: Gutter-Profil-Cut + Flood-
Fallback + Merge), in Lesereihenfolge sortiert (`ReadingOrder`) und eine
Index-Navigation über Seiten anbietet (`GuidedNavigator`). Die eigentliche
Guided-View-Logik (welche Kachel als Nächstes, Crop/Zoom, Position halten)
liegt aber noch in der konsumierenden App (komga-reader).

**Ziel:** Die Lib wird *die* entkoppelte Guided-Engine. Eine App ruft je
Seitenbild `PanelGuide().guide(page)` und bekommt die Kacheln in Anzeige-
Reihenfolge (oder die Vollseite als Fallback) — die rohe Sortier-/Fallback-
Heuristik ist damit aus der App raus. Die Seiten-Verwaltung (welche Seite,
Sprung, Fortschritt, Position) bleibt bewusst beim Reader. Zusätzlich wird die
Panel-Quelle pluggbar: neben dem geometrischen Detektor kann ein **ML-Modell**
(YOLO11n, on-edge) Panels liefern.

Diese zwei Funktionen hängen an **einer Naht** — einer `PanelSource`-
Schnittstelle. Darum wird die Naht zuerst gelegt; ML und Heuristik sind danach
Steckmodule, kein Rewrite.

## 2. Architektur — Modul-Schnitt (Variante C)

```
comic-cutter            reiner Kern, NIE Native-Dep (KMP jvm+js)
  ├ PanelSource           interface — woher kommen rohe Boxen
  ├ GeometricPanelSource  wrappt heutigen PanelDetector
  ├ ModelRunner           interface — wer führt das Modell aus (injiziert)
  ├ RawDetection          roh-Box aus dem Modell (x,y,w,h,score,cls)
  ├ MlPanelSource         RawDetection[] → PanelRect[]  (conf-Filter + NMS + min-area, rein)
  ├ MlFilter              conf-Threshold / NMS-IoU / min-area-Parameter
  └ PanelGuide            guide(page): Bild → geordnete Crop-Rects + Vollseiten-Fallback
comic-cutter-onnx-jvm   optionales Extra-Artefakt: OnnxModelRunner : ModelRunner
                          (ONNX-Runtime + Native lebt NUR hier)
```

**Leitprinzip:** Functional Core, Imperative Shell. Der ML-Runner ist I/O
(Modell laden, Native-Lib, Tensor-Speicher) und gehört an den Rand. Der Kern
(`comic-cutter`) bleibt rein, testbar, KMP-sauber, ohne Native-Dep.

**Beide Türen offen:** Eine App kann
- `ModelRunner` selbst implementieren (eigene Inferenz, Variante-A-Pfad), oder
- `comic-cutter-onnx-jvm` ziehen und den fertigen `OnnxModelRunner` nutzen
  (Variante-B-Bequemlichkeit), oder
- ganz ohne ML nur `GeometricPanelSource` verwenden.

Alle drei laufen gegen *dieselbe* `PanelSource`/`PanelGuide`-API.

> Ausrichtung: Das Tuning-Repo `komga-reader-guided-comic` nutzt bereits einen
> `DetectorSeam` — dieselbe Naht-Idee. Die hier definierte `PanelSource` ist die
> produktive Entsprechung.

## 3. Die Naht

```kotlin
/** Roh-Boxen einer Seite, UNsortiert. Quelle = Detektor oder ML. */
fun interface PanelSource {
    fun detect(page: RenderedPage): List<PanelRect>
}

/** Wer das Modell tatsächlich ausführt. App ODER comic-cutter-onnx-jvm. */
interface ModelRunner {
    fun infer(page: RenderedPage): List<RawDetection>
}

/** Eine rohe Modell-Detektion vor Filterung (Pixel-Koordinaten). */
data class RawDetection(
    val x: Int, val y: Int, val width: Int, val height: Int,
    val score: Float, val cls: Int,
)
```

- `GeometricPanelSource` wrappt den heutigen `PanelDetector` hinter dem
  Interface (liefert roh, das Sortieren übernimmt `PanelGuide`).
- `MlPanelSource(runner: ModelRunner, filter: MlFilter)` ist reines Mapping:
  `runner.infer(page)` → conf-Threshold → NMS (IoU) → min-area → `PanelRect[]`.
  Der Runner ist injiziert; das Mapping ist pur und voll testbar ohne Modell.

## 4. MlPanelSource — Mapping & Filter

```kotlin
data class MlFilter(
    val minScore: Float = 0.30f,     // entspricht der webtool-Default-conf
    val nmsIoU: Float = 0.50f,
    val minAreaFraction: Float = 0.01f,  // wie PanelDetector.minPanelAreaFraction
    val keepClass: Int? = null,      // null = alle Klassen; YOLO-Panel-Modell hat 1 Klasse
)
```

Pipeline (rein, deterministisch):
1. `score >= minScore` filtern.
2. NMS über `nmsIoU` (IoU-Geometrie existiert im Tuning-Repo als `Iou.kt` —
   gleiche Formel hier im Kern neu, klein und testbar).
3. `keepClass` filtern, falls gesetzt.
4. Boxen kleiner `minAreaFraction * pageArea` verwerfen.
5. `RawDetection` → `PanelRect`.

Sortierung passiert NICHT hier, sondern zentral in `PanelGuide` (eine Quelle
für die Reihenfolge). `MlPanelSource` liefert ungeordnete `PanelRect`.

## 5. PanelGuide — die extrahierte Heuristik (per Seite, zustandslos)

**Grenze (wichtig):** Die Lib bekommt *ein Bild* und sagt, welche Kacheln es hat
und in welcher Reihenfolge sie anzuzeigen sind. Die **Seiten-Verwaltung** —
welche Seite gerade dran ist, Seitensprung, Fortschritt, Position merken/
wiederherstellen — bleibt beim aufrufenden **Reader**. Die Lib hält dafür keinen
State und navigiert nicht über Seitengrenzen.

```kotlin
/** Geführte Anzeige-Schritte für EIN Seitenbild. steps ist nie leer (min. ein Schritt). */
data class PageGuide(
    val steps: List<NormRect>,   // geordnete Crop-Rects (0..1), Comics: links→rechts
    val isFullPage: Boolean,     // true = <2 Kacheln → steps = [ NormRect(0,0,1,1) ]
)

class PanelGuide(private val source: PanelSource = GeometricPanelSource()) {
    fun guide(page: RenderedPage): PageGuide   // detect → LTR-sort → normalize → Fallback
}
```

Reader-seitig (dessen Logik, ~3 Zeilen):

```kotlin
val g = panelGuide.guide(currentPageImage)   // Reader hält die Seite
showStep(g.steps[unit])                       // "next.kachel" = unit++ in steps
// steps durch? → Reader lädt die nächste Seite selbst und ruft guide erneut
```

**Verantwortlichkeiten von `guide(page)`:**

- **Sortierung:** intern `ReadingOrder.sort(boxes, LEFT_TO_RIGHT)`. Leserichtung
  **fix links→rechts** (nur Comics) — kein Richtungs-Parameter, kein Auto-Detect.
- **Confidence-Fallback:** Liefert die Quelle <2 Kacheln (Splash, Schrott-
  Detection), ist die ganze Seite ein Schritt (`isFullPage=true`,
  `steps = [NormRect(0,0,1,1)]`). Der Reader sieht nie kaputte Kacheln.
- **Crop-Rect:** jeder `step` ist die bild-normalisierte `NormRect` einer Kachel
  (via `PanelGeometry.normalize`).
- **Synchron, zustandslos:** reine Bild→Kacheln-Funktion. Threading (ML on-edge
  im Hintergrund) macht der Reader; Caching der erkannten Kacheln je Seite
  ebenfalls (er besitzt die Seiten). Deshalb kein `suspend`, keine coroutines-Dep.

**Kein Viewport-Wissen in der Lib.** Die App besitzt bereits
`PanelGeometry.fitScale` (Contain + Margin + Pivot=Panel-Mitte), das aus einer
`NormRect` zur Render-Zeit den Zoom-Faktor für den *konkreten* Viewport berechnet.
Padding/Aspect bleiben damit Render-seitig (`marginFraction`) — die Lib liefert
die Geometrie, die App fügt den Viewport hinzu.

> **Hinweis:** Das bestehende `GuidedNavigator` (Cross-Page-Indexlogik) bleibt
> als pre-existing API im Repo, wird von `PanelGuide` aber nicht mehr genutzt —
> Seiten-Navigation ist jetzt Reader-Sache.

## 6. ML-Asset & Provenance

Integriert wird das im Webtool als **`yolo_v3` / „YOLO11n v3"** benannte Modell
(Ultralytics YOLO11n, eine Klasse „panel").

| Feld | Wert |
|------|------|
| Name | YOLO11n v3 (webtool-Engine-ID `yolo_v3`) |
| Architektur | Ultralytics YOLO11n, single-class „panel" |
| Inferenz-Setup | `conf=0.30`, `imgsz=1024`, xyxy→`[x,y,w,h]`+score |
| Herkunft | Eigenes Training (`komga-yolo-spike/yolo`, `yolo11n_v{1,2,3}.pt`) |
| On-Edge-Format | ONNX-Export (für ONNX-Runtime jvm / ort-web wasm) |
| Lizenz Gewichte | inoffiziell/privat (nur für eigenen Gebrauch) |
| Lizenz Architektur | Ultralytics YOLO11 = **AGPL-3.0** (passt zur Lib-Lizenz) |

> **Provenance-Pflicht:** Das ONNX-Modell ist ein externes ML-Asset. Eine
> Provenance-Datei (`models/PROVENANCE.md` o. ä.) mit obigen Feldern + exaktem
> Trainings-Dataset-Verweis + Erfassungsdatum wird im selben Commit angelegt,
> der das Modell/den Runner einbindet. Risk-Notiz: Ultralytics-AGPL gilt für die
> Architektur; das deckt sich mit der bestehenden Lib-Lizenz (AGPL-3.0).

> **Trainings-Dataset (Verweis, nicht Teil dieses Specs):** golden-age PD-Sampler
> + manga/comic-Labels aus `komga-reader-guided-comic/dataset` und
> `komga-yolo-spike/yolo/dataset`. Genaue Quellen/Caps gehören in die Provenance
> des Modell-Trainings, nicht in die Lib.

Exakter Export-Pfad, ONNX-Opset und Pre-/Post-Processing (Letterbox auf
`imgsz=1024`, Output-Tensor-Shape) werden in der Plan-Phase festgelegt; sie sind
Implementierungsdetail des `OnnxModelRunner`.

## 7. Was sich für die App ändert

- **Vorher:** App kennt den Detektor, sortiert selbst, rechnet Crops, entscheidet
  Vollseiten-Fallback.
- **Nachher:** App ruft je Seitenbild `PanelGuide().guide(page)` und bekommt
  `PageGuide(steps, isFullPage)`. „next.kachel" = das nächste Element in `steps`
  (Reader zählt selbst); jeden `step` reicht sie in ihr bestehendes `fitScale` →
  „fahr dahin". Seiten-Verwaltung (welche Seite, Sprung, Fortschritt, Position
  merken) bleibt beim Reader — die rohe Sortier-/Fallback-Heuristik nicht mehr.
- Die bestehende `PanelDetector`-API und die `DetectorJs`-Browser-API bleiben
  rückwärtskompatibel (additive Änderung; `GeometricPanelSource` wrappt den
  Detektor, ersetzt ihn nicht).

## 8. Testing (TDD)

Kern ist rein → ideal für TDD. Test-Liste:
- `MlPanelSource`: conf-Filter, NMS (überlappende Boxen → eine), min-area,
  `keepClass`, RawDetection→PanelRect-Mapping. Mit Fake-`ModelRunner`, kein
  echtes Modell.
- `PanelGuide`: LTR-Reihenfolge + Normalisierung der Kacheln (Fake-Source mit
  unsortierten Boxen), Fallback bei <2 Kacheln (`isFullPage`, `steps =
  [NormRect(0,0,1,1)]`), leere Seite → Vollseite.
- `GeometricPanelSource`: liefert dieselben Boxen wie `PanelDetector.detect`
  (unsortiert) — Regressions-Anker.
- `OnnxModelRunner` (jvm-Modul): Integrationstest mit echtem Mini-ONNX gegen
  bekannte Seite; nicht im common-Test.

## 9. Offene Mini-Punkte (Plan-Phase)

- `ModelRunner` als `expect/actual` vs. reines `interface` + Injection →
  Injection bevorzugt (Kern bleibt frei von Plattform-Splits).
- IoU/NMS klein im Kern neu implementieren vs. aus Tuning-Repo kopieren →
  neu (klein, testbar, keine Quer-Dep).

## 10. Out-of-Scope (YAGNI, v1)

- Seiten-Verwaltung in der Lib (Cross-Page-Navigation, Position-State, Cache,
  Prefetch) — bewusst Reader-Sache; die Lib ist per-Seite und zustandslos.
- `comic-cutter-ort-wasm` (Browser-Runner) — erst wenn ein Browser-Konsument
  ML braucht; die Demo nutzt weiter den geometrischen Pfad.
- Manga/RTL, Webtoon-vertikal, Auto-Leserichtung.
- Sub-Panel-Stepping innerhalb großer Panels.
