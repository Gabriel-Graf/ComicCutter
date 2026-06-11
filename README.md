# GuidedComic — Comic-Cutter

Reiner-Kotlin Detektor für Comic-/Manga-**Panels**: erkennt die Kacheln einer
gerenderten Seite, sortiert sie in Lesereihenfolge und liefert eine geführte
Panel-zu-Panel-Navigation. **Host-unabhängig** — kein AWT, kein `ImageIO`,
keine Fremd-Abhängigkeit außer der Kotlin-Stdlib. Läuft damit auch headless
(z. B. in einem Server wie Komga) — und dank Kotlin/JS sogar direkt im Browser.

## Live-Demo

Panel-Erkennung direkt im Browser, ohne Backend:
**https://gabriel-graf.github.io/ComicGuide/**

Gemeinfreie Beispielseiten (Quelle/Lizenz sichtbar verlinkt) oder eigenes Bild
hochladen — die Erkennung läuft komplett lokal, nichts wird hochgeladen.

## Ansatz

Primärpfad ist ein color-agnostischer **Profil-XY-Cut** (`GutterProfileCut`),
der helle *und* dunkle Gassen über Projektions-Statistik trennt und auch von
Sprechblasen überbrückte Gutter toleriert. Findet er keine Gasse (full-bleed-
Splash), greift ein **Flood-Fill-Fallback** über das vom Rand zusammenhängende
Weißgutter-Netz. Ein Merge-Pass verschmilzt über interne Kanten zersplitterte
Kacheln anhand von Content-Kontinuität.

## Einbinden

> Erfordert JVM 21+. Ab **0.2.0** ist die Lib Kotlin-Multiplatform (jvm + js).
> Gradle-Konsumenten (z. B. Komga) nutzen die Koordinate unten unverändert —
> Gradle löst via Modul-Metadata automatisch auf das `-jvm`-Artefakt auf.

### Variante A — JitPack (Tag genügt, keine Server-Infrastruktur)

```kotlin
repositories {
    mavenCentral()
    maven("https://jitpack.io")
}
dependencies {
    implementation("com.github.Gabriel-Graf:ComicGuide:0.2.0")
}
```

### Variante B — Maven Central (sobald veröffentlicht)

```kotlin
dependencies {
    implementation("io.github.gabriel-graf:comic-cutter:0.2.0")
}
```

## Nutzung

```kotlin
import com.panela.comiccutter.PanelDetector
import com.panela.comiccutter.ReadingDirection
import com.panela.comiccutter.model.RenderedPage

// Seite als ARGB-Pixel (host-seitig aus BufferedImage o. ä. befüllen):
//   val px = IntArray(w * h); img.getRGB(0, 0, w, h, px, 0, w)
val page = RenderedPage(width, height, px)

val panels = PanelDetector().detect(page, ReadingDirection.RIGHT_TO_LEFT)
// → List<PanelRect> in Lesereihenfolge
```

Für die geführte Navigation siehe `GuidedNavigator` / `GuidedPosition`.

## Public API

| Typ | Zweck |
|-----|-------|
| `PanelDetector` | Panel-Erkennung: `detect(page, direction): List<PanelRect>` |
| `RenderedPage` | Host-unabhängige Seite: `(width, height, IntArray /*ARGB*/)` |
| `ReadingDirection` | `LEFT_TO_RIGHT` / `RIGHT_TO_LEFT` |
| `PanelRect` / `NormRect` | Panel-Bounding-Box (Pixel bzw. normalisiert) |
| `GuidedNavigator` | Panel-zu-Panel-Navigation über Seiten |

## Bauen

```bash
./gradlew build               # kompilieren + Tests (jvm + js)
./gradlew publishToMavenLocal # nach ~/.m2 (lokales Einbinden)
./gradlew jsBrowserDistribution # JS-Bundle für die Demo
```

Demo lokal: Bundle nach `demo/src/kotlin/` kopieren, dann `cd demo && npm install && npm run dev`.
Deploy nach GitHub Pages läuft automatisch über `.github/workflows/pages.yml`.

## Lizenz

[AGPL-3.0](LICENSE). Einbindende Projekte unterliegen damit dem AGPL-Copyleft
(inkl. Netzwerk-Klausel).
