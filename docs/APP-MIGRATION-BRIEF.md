# Migrations-Brief: komga-reader auf die ComicCutter-Lib umstellen

**Ziel:** Die lokale Guided-View-Heuristik der App durch die **ComicCutter**-Lib
ersetzen — App wird von der Heuristik entkoppelt. Die App ruft für „next_panel"
nur noch `PanelGuide.guide(page)` und bekommt die Kacheln in Anzeige-Reihenfolge.

**Kernregel:** Es wird **kein Heuristik-Code in die App kopiert.** Die App
bekommt eine Dependency auf die Lib und **löscht** ihre eigene Kopie. Eine Quelle
der Wahrheit. Die Lib-Heuristik ist neuer/stärker als der App-Stand (sie hat
zusätzlich `GutterProfileCut` als Primärpfad, Merge-Pass, Downscale, Flood-
Arbitrierung) — die Erkennung wird durch die Umstellung also besser, nicht nur
gleich.

---

## 1. Dependency hinzufügen

**Jetzt (lokal, sofort baubar):** Die Lib ist als `0.3.0` in MavenLocal publiziert.

```kotlin
// settings.gradle.kts / build.gradle.kts der App
repositories {
    mavenLocal()
    mavenCentral()
}
dependencies {
    implementation("io.github.gabriel-graf:comic-cutter:0.3.0")
    // Gradle löst via KMP-Metadata automatisch auf das -jvm-Artefakt auf.
}
```

**Später (echtes Release, CI):** Sobald ein Tag `0.3.0` nach GitHub gepusht ist:

```kotlin
repositories { maven("https://jitpack.io") }
dependencies { implementation("com.github.Gabriel-Graf:ComicCutter:0.3.0") }
```

> Optionales ML-Modul `comic-cutter-onnx-jvm` (ONNX-Runner) wird **noch nicht**
> gebraucht — „next_panel" läuft über den geometrischen Detektor (Default). ML
> ist ein späterer additiver Schritt.

---

## 2. Was die App AUFRUFT (Public API der Lib)

Alles im Paket **`com.panela.comiccutter`** (nicht mehr `com.komgareader.comiccutter`):

```kotlin
import com.panela.comiccutter.PanelGuide
import com.panela.comiccutter.PageGuide
import com.panela.comiccutter.NormRect
import com.panela.comiccutter.PanelGeometry
import com.panela.comiccutter.model.RenderedPage

// EIN Bild rein → Kacheln in Anzeige-Reihenfolge raus (zustandslos, synchron):
val guide: PageGuide = PanelGuide().guide(renderedPage)
//   guide.steps      : List<NormRect>  — geordnet LTR, normalisiert 0..1
//   guide.isFullPage : Boolean         — true = <2 Kacheln → steps == [NormRect(0,0,1,1)]
```

`PanelGuide()` nutzt per Default den geometrischen Detektor
(`GeometricPanelSource()`). Kein Parameter nötig.

Weiter nutzbar für das Rendering (unverändert zur alten App-Geometrie):
`PanelGeometry.fitScale(...)`, `PanelGeometry.normalize(...)`,
`PanelGeometry.hitTest(...)`, Typ `NormRect(left, top, width, height: Float)`.

---

## 3. RenderedPage-Adapter

Die Lib hat ihren eigenen `RenderedPage`-Typ (`com.panela.comiccutter.model`),
gleiche Form wie der App-Typ. Die App rendert die Seite weiterhin selbst zu
ARGB-Pixeln und baut daraus den Lib-Typ:

```kotlin
fun com.komgareader.domain.render.RenderedPage.toLib() =
    com.panela.comiccutter.model.RenderedPage(width, height, pixels)
// pixels: rohe ARGB_8888, row-major (i = y*width + x) — identisches Layout.
```

---

## 4. „next_panel" — die Reader-Schleife (bleibt in der App)

Die **Seiten-Verwaltung bleibt komplett beim Reader**: welche Seite, Seitensprung,
Fortschritt, Position merken/wiederherstellen. Die Lib navigiert NICHT über
Seitengrenzen.

```kotlin
// Reader hält currentPage + unit selbst:
val g = panelGuide.guide(currentPage.toLib())   // pro Seite einmal; Reader cacht das Ergebnis
val target: NormRect = g.steps[unit]            // "next_panel" = unit++ in g.steps
//   unit über g.steps hinaus  → Reader lädt die nächste Seite, ruft guide() neu, unit=0
//   unit < 0                  → Reader geht eine Seite zurück, unit = letzteSteps.lastIndex
showStep(target)                                 // target in PanelGeometry.fitScale(...) → "fahr dahin"
//   g.isFullPage == true     → ganze Seite zeigen (Fallback bei <2 Kacheln)
```

`guide()` ist blockierend (reine CPU-Heuristik) → auf einem Hintergrund-
Dispatcher aufrufen. Das Ergebnis je Seite cachen (Reader besitzt die Seiten).

---

## 5. Was GELÖSCHT wird

Die lokale Heuristik-Kopie der App — **vollständig entfernen**, sie ist jetzt die
Lib:

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

…sowie deren Tests, falls sie nur die gelöschte Kopie absichern (das Verhaltens-
Netz lebt jetzt in der Lib).

## 6. Was BLEIBT in der App

- Seiten laden/rendern → ARGB (`RenderedPage`-Quelle).
- Paging, Lese-Position, Fortschritt, Position-Persistenz/Restore.
- Viewport-Rendering / Zoom (darf weiter `PanelGeometry.fitScale` nutzen).
- `RenderedPage`-Typ der App bleibt; nur der `.toLib()`-Adapter kommt dazu.

---

## 7. Hinweise

- **Leserichtung:** `PanelGuide` ist **fix links→rechts** (Comics). Braucht die
  App doch RTL/Manga, nicht `PanelGuide`, sondern direkt
  `ReadingOrder.sort(GeometricPanelSource().detect(page), ReadingDirection.RIGHT_TO_LEFT)`
  + `PanelGeometry.normalize`. (Laut Scope: nur Comics → `PanelGuide` reicht.)
- **Erkennung ändert sich leicht:** Die Lib-Heuristik ist besser als die alte
  App-Kopie — Panel-Boxen können minimal anders ausfallen (i. d. R. korrekter).
  Kein Bug.
- **Verifikation:** App baut grün; auf einer bekannten Mehr-Panel-Seite liefert
  `guide(page).steps.size >= 2` und `isFullPage == false`.

---

## 8. ML später (nicht jetzt)

Wenn ein YOLO-Panel-Modell rein soll, ohne die App zu ändern: nur die `source`
in `PanelGuide` tauschen —
`PanelGuide(MlPanelSource(runner, MlFilter(minScore = 0.25f, nmsIoU = 0.7f)))`.
Den Runner liefert das optionale Modul `comic-cutter-onnx-jvm`
(`OnnxModelRunner(modelBytes)`); das Modell selbst reicht der Reader zur Laufzeit
rein. Die `guide()`-Aufrufe in der App bleiben identisch.
