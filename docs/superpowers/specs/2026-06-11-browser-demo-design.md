# ComicGuide — Browser-Demo (Design-Spec)

**Datum:** 2026-06-11
**Status:** Genehmigt (Brainstorming abgeschlossen)
**Repo:** Gabriel-Graf/ComicGuide

## Ziel

Eine **statische, gratis auf GitHub Pages gehostete** Demo, die den reinen-Kotlin
Panel-Detektor direkt **im Browser** ausführt — ohne Backend, ohne Server. Nutzer
können mitgelieferte gemeinfreie Golden-Age-Comicseiten ansehen oder **eigene
Bilder hochladen** und sehen die erkannten Panels als farbiges Overlay mit
Lesereihenfolge.

**Nicht-Ziele (YAGNI):** kein Box-Editing/Speichern, kein YOLO/Quellen-Vergleich
(bräuchte Server/ONNX), kein Filmstrip, keine geführte Lese-Navigation. Reines
Anzeigen der Erkennung.

## Kern-Idee

Der Detektor ist reines Kotlin (kein `java.*`, kein AWT — verifiziert). Damit lässt
er sich nach **Kotlin/JS** kompilieren und läuft vollständig clientseitig. Die Lib
wird auf **Kotlin-Multiplatform** umgestellt: ein gemeinsamer `commonMain`-Code,
ein `jvm()`-Target (weiterhin für Komga) und ein `js(IR)`-Browser-Target (für die
Demo). Ein Code, beide Welten — die Demo testet den *echten* Detektor.

## Architektur

```
ComicGuide/
├── src/
│   ├── commonMain/kotlin/com/panela/comiccutter/…   ← Detektor (aus src/main verschoben)
│   ├── commonTest/kotlin/com/panela/comiccutter/…   ← Tests (laufen auf jvm UND js)
│   └── jsMain/kotlin/com/panela/comiccutter/js/DetectorJs.kt  ← @JsExport-Fassade
├── build.gradle.kts        ← kotlin("multiplatform"): jvm() + js(IR){ browser() }
├── demo/                   ← statische Vite+TS-App (kein Backend)
│   ├── index.html
│   ├── package.json, vite.config.ts, tsconfig.json
│   ├── src/main.ts         ← UI, Datenfluss, Overlay-Rendering
│   ├── src/detector.ts     ← Typ-Wrapper um das Kotlin/JS-Bundle
│   ├── comics/             ← ~10 runterskalierte PD-Seiten (JPG)
│   ├── comics.json         ← [{ file, title, year }]
│   └── PROVENANCE.md       ← Herkunft/Lizenz/Datum je Quelle
└── .github/workflows/pages.yml  ← baut JS + Demo, deployed nach Pages
```

### Lib-Umstellung (v0.1.0 → v0.2.0)

- `build.gradle.kts`: Plugin `kotlin("jvm")` → `kotlin("multiplatform")`.
  Targets: `jvm { withJavadocJar()/withSourcesJar()-Äquivalent }` + `js(IR) { browser(); binaries.executable() }`.
- Quellen verschieben: `src/main/kotlin` → `src/commonMain/kotlin`,
  `src/test/kotlin` → `src/commonTest/kotlin`. Keine Code-Änderung am Detektor nötig
  (Code ist bereits common-kompatibel).
- **Publishing:** KMP publisht ein Root-Modul (Gradle-Metadata) + `comic-cutter-jvm`.
  Komga (Gradle) zieht `io.github.gabriel-graf:comic-cutter:0.2.0` und Gradle löst
  automatisch auf das `-jvm`-Artefakt auf. Reine Maven-Konsumenten müssten `-jvm`
  explizit nehmen — für Komga (Gradle) irrelevant.
- `version = "0.2.0"`. Der `v0.1.0`-Tag (reine JVM-Lib) bleibt unberührt bestehen.

### JS-Fassade (`DetectorJs.kt`, jsMain)

Dünne, JS-freundliche Schicht über `PanelDetector`. Gibt **normalisierte**
Koordinaten (0..1) zurück, damit das Overlay unabhängig von der Anzeigegröße ist.

```kotlin
@JsExport
fun detectPanels(width: Int, height: Int, argb: IntArray, rtl: Boolean): Array<PanelJs>

@JsExport
class PanelJs(
    val left: Double, val top: Double,
    val width: Double, val height: Double,
    val order: Int,   // 1-basierte Lesereihenfolge
)
```

Intern: `RenderedPage(width, height, argb)` → `PanelDetector().detect(page, dir)` →
Pixel-`PanelRect` auf 0..1 normalisieren → `PanelJs[]`. `rtl` wählt
`ReadingDirection.RTL` bzw. `LTR`.

> Kotlin/JS bildet `IntArray` auf `Int32Array` ab — die Demo übergibt also ein
> `Int32Array` mit ARGB-Werten.

## Demo: Datenfluss

1. Nutzer wählt eine Beispielseite **oder** lädt ein Bild hoch (Datei-Input + Drag&Drop).
2. Bild → `ImageBitmap`/`<img>` → auf ein **Offscreen-Canvas** zeichnen.
3. Falls längste Kante > **1500 px**: proportional auf 1500 px runterskalieren
   (Detektor-Output ist normalisiert → kein Qualitätsverlust fürs Overlay, aber
   deutlich schneller).
4. `ctx.getImageData()` → RGBA-`Uint8ClampedArray`.
5. RGBA → ARGB `Int32Array`: `(a<<24)|(r<<16)|(g<<8)|b` pro Pixel.
6. `detectPanels(w, h, argb, rtl)` aufrufen.
7. Ergebnis als absolut positioniertes Overlay über dem **angezeigten** Bild
   rendern: farbige Rechtecke (left/top/width/height in %), Reihenfolge-Badge je Box.
8. Controls: **RTL/LTR-Toggle** (Default RTL für Manga, LTR für US-Comics — Default
   LTR, da Beispiele US-Golden-Age), Auswahl-Dropdown der Beispiele, Upload.

Rendering lehnt sich optisch an den Readonly-Overlay-Stil des bestehenden
`CanvasEditor` an (Rechteck + Nummern-Badge), aber als eigenständige, schlanke
Komponente ohne Editier-Logik.

## Beispiel-Comics (Public Domain)

- ~10 **Einzelseiten** gemeinfreier US-Golden-Age-Hefte (Erstveröffentlichung vor
  1964 ohne Copyright-Renewal → Public Domain), Quelle Digital Comic Museum /
  Comic Book Plus.
- Je 1 repräsentative Seite mit mehreren Panels (zeigt die Erkennung gut),
  runterskaliert (längste Kante ~1500 px, JPG q~80) → Repo bleibt schlank
  (~2–4 MB gesamt statt hunderte MB).
- Konkrete Titelliste wird in der Umsetzung vorgeschlagen und vom Nutzer abgehakt,
  **bevor** Bilder eingebunden werden.
- `PROVENANCE.md` dokumentiert je Quelle: Name, URL (Permalink), Lizenz
  (Public Domain), Erfassungsdatum, Risk-Notiz. Pflicht gemäß Provenance-Regel.

## Deploy (GitHub Actions → Pages)

`.github/workflows/pages.yml`, Trigger auf `main`-Push:

1. `actions/checkout`, `actions/setup-java` (Temurin 21).
2. `./gradlew jsBrowserDistribution` → erzeugt das JS-Bundle.
3. JS-Bundle nach `demo/public/` (oder via Vite-Import) bereitstellen.
4. `npm ci && npm run build` im `demo/` → statisches `dist/`.
5. `actions/upload-pages-artifact` + `actions/deploy-pages`.

Pages-Quelle: GitHub Actions. Ergebnis-URL: **gabriel-graf.github.io/ComicGuide**.

## Fehlerbehandlung

- Upload ist kein dekodierbares Bild → nicht-blockierender Hinweis-Banner.
- Erkennung liefert 0 Panels → Hinweis „keine Panels gefunden" (z. B. bei
  full-bleed-Splash; legitimes Detektor-Ergebnis, kein Fehler).
- JS-Bundle nicht geladen → klare Konsolen-/UI-Meldung statt stiller Fehlschlag.

## Testing

- **Bestehende `commonTest`-Tests** laufen nach der Umstellung auf **beiden**
  Targets (`jvmTest` + `jsTest`) — verifiziert, dass der Detektor in JS dasselbe
  liefert wie auf der JVM.
- Ein schlanker `jsTest` für die Fassade: bekanntes synthetisches ARGB-Bild
  (z. B. zwei Kacheln mit heller Gasse) → `detectPanels` liefert 2 normalisierte
  Boxen in erwarteter Reihenfolge.
- Demo-UI: manuelle Verifikation (Bild rein → Overlay stimmt). Keine UI-Unit-Tests
  (rein visuelles Layout, YAGNI).

## Risiken & Mitigation

| Risiko | Mitigation |
|--------|------------|
| KMP-Publishing ändert Artefakt-Koordinaten | Komga ist Gradle → Metadata-Auflösung; in README dokumentieren; `v0.1.0` bleibt als JVM-Fallback |
| Detektor zu langsam auf großen Bildern im Browser | Runterskalierung auf 1500 px vor `detect` |
| Lizenz-Unsicherheit bei Comics | Nur belegt-gemeinfreie Titel, `PROVENANCE.md`, Nutzer-Freigabe vor Einbindung |
| `@JsExport` + IntArray-Interop-Stolpersteine | Fassade dünn halten, `jsTest` deckt den Interop-Pfad ab |

## Offene Punkte (in der Umsetzung zu klären)

- Konkrete 10 Comic-Titel (Nutzer-Freigabe).
- Default-Leserichtung der Demo (Vorschlag: LTR, da US-Beispiele).
