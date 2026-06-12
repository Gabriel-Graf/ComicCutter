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

**Ziel:** Die Lib wird *die* entkoppelte Guided-Engine. Eine App baut einmal
einen `GuidedReader` und ruft nur noch `next()` / `previous()` / `fullPage()` —
null Guided-Logik in der App. Zusätzlich wird die Panel-Quelle pluggbar: neben
dem geometrischen Detektor kann ein **ML-Modell** (YOLO11n, on-edge) Panels
liefern.

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
  └ GuidedReader          Facade: Panel-Cache + Position + Crop-Rect + Fallback
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

Alle drei laufen gegen *dieselbe* `PanelSource`/`GuidedReader`-API.

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
  Interface (liefert roh, das Sortieren übernimmt `GuidedReader`).
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

Sortierung passiert NICHT hier, sondern zentral in `GuidedReader` (eine Quelle
für die Reihenfolge). `MlPanelSource` liefert ungeordnete `PanelRect`.

## 5. GuidedReader — die extrahierte Heuristik

```kotlin
class GuidedReader(
    private val source: PanelSource,
    private val pageCount: Int,
    private val loadPage: suspend (Int) -> RenderedPage,
) {
    suspend fun start(): GuidedStep                 // erste Einheit der ersten Seite
    suspend fun next(): GuidedStep?                 // nächstes Panel, über Seitengrenzen; null am Ende
    suspend fun previous(): GuidedStep?             // ein Panel zurück, über Seitengrenzen; null am Anfang
    suspend fun fullPage(page: Int): GuidedStep     // ganze Seite als Einheit, falls gewollt
    fun position(): GuidedPosition                  // aktueller State (für Persistenz/Restore)
}

/** Ein Schritt im geführten Lesefluss. */
data class GuidedStep(
    val page: Int,
    val rect: NormRect,        // bild-normalisiertes Ziel-Rechteck (0..1)
    val isFullPage: Boolean,   // true = Fallback / fullPage(): ganze Seite
)
```

**Verantwortlichkeiten:**

- **Panel-Cache je Seite:** Die (teure) `source.detect` läuft genau einmal pro
  Seite; Ergebnis gecacht. Spätere `next/previous` auf derselben Seite treffen
  den Cache. (Prefetch der Folgeseite ist optional / Out-of-scope v1, siehe §10.)
- **Sortierung:** intern `ReadingOrder.sort(boxes, LEFT_TO_RIGHT)`. Leserichtung
  ist **fix links→rechts** (nur Comics) — kein Richtungs-Parameter nach außen,
  kein Auto-Detect.
- **Position-State:** hält `GuidedPosition(page, unit)`; `next/previous` nutzen
  die bestehende `GuidedNavigator`-Indexlogik mit `unitsAt = cache-Lookup`.
- **Confidence-Fallback:** Liefert die Quelle <2 Panels (echter Splash, Schrott-
  Detection), hat die Seite genau 1 Einheit = ganze Seite, `isFullPage=true`.
  Der Reader sieht nie kaputte Panels.
- **Crop-Rect:** `GuidedStep.rect` ist die bild-normalisierte `NormRect` des
  Panels (via `PanelGeometry.normalize`). `fullPage` → `NormRect(0,0,1,1)`.

**Kein Viewport-Wissen in der Lib.** Die App besitzt bereits
`PanelGeometry.fitScale` (Contain + Margin + Pivot=Panel-Mitte), das aus der
`NormRect` zur Render-Zeit den Zoom-Faktor für den *konkreten* Viewport
berechnet. Padding/Aspect bleiben damit Render-seitig (`marginFraction`),
konsistent mit dem heutigen Code — die Lib liefert die Geometrie, die App fügt
den Viewport hinzu. `GuidedReader` braucht deshalb keinen `viewportAspect`.

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

- **Vorher:** App kennt den Detektor, sortiert selbst, rechnet Crops, hält die
  Lese-Position, hat Fallback-Logik.
- **Nachher:** App baut einmal `GuidedReader(source, pageCount, loadPage)` und
  ruft `next()` / `previous()` / `fullPage()`. Den `GuidedStep.rect` reicht sie
  in ihr bestehendes `fitScale` → „fahr dahin". Keine Guided-Logik mehr in der
  App.
- Die bestehende `PanelDetector`-API und die `DetectorJs`-Browser-API bleiben
  rückwärtskompatibel (additive Änderung; `GeometricPanelSource` wrappt den
  Detektor, ersetzt ihn nicht).

## 8. Testing (TDD)

Kern ist rein → ideal für TDD. Test-Liste:
- `MlPanelSource`: conf-Filter, NMS (überlappende Boxen → eine), min-area,
  `keepClass`, RawDetection→PanelRect-Mapping. Mit Fake-`ModelRunner`, kein
  echtes Modell.
- `GuidedReader`: Cache (Quelle nur 1× je Seite — Spy-Source mit Zähler),
  `next/previous` über Seitengrenzen, Fallback bei <2 Panels (`isFullPage`),
  `fullPage` → `NormRect(0,0,1,1)`, LTR-Reihenfolge, Position-Restore.
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

- Seiten-Prefetch / Lookahead-Threading (`loadPage` ist `suspend`, Prefetch
  kann später additiv dazu).
- `comic-cutter-ort-wasm` (Browser-Runner) — erst wenn ein Browser-Konsument
  ML braucht; die Demo nutzt weiter den geometrischen Pfad.
- Manga/RTL, Webtoon-vertikal, Auto-Leserichtung.
- Sub-Panel-Stepping innerhalb großer Panels.
