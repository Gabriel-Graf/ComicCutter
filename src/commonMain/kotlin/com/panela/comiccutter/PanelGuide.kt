package com.panela.comiccutter

import com.panela.comiccutter.model.RenderedPage

/** Geführte Anzeige-Schritte für EIN Seitenbild. [steps] ist nie leer (min. ein Schritt). */
data class PageGuide(
    /** Geordnete Crop-Rects in bild-normalisierten Koordinaten (0..1), Comics: links→rechts. */
    val steps: List<NormRect>,
    /** true, wenn die ganze Seite ein Schritt ist (Fallback bei <2 erkannten Kacheln). */
    val isFullPage: Boolean,
)

/**
 * Wandelt ein Seitenbild in geführte Anzeige-Schritte: erkennt die Kacheln über die [source],
 * sortiert sie in Lesereihenfolge (Comics: links→rechts) und normalisiert sie auf [0..1].
 * Bei <2 Kacheln (Splash oder unsichere Erkennung) ist die ganze Seite ein Schritt.
 *
 * Reine Bild→Kacheln-Funktion ohne State: Die Seiten-Verwaltung (welche Seite, Sprung,
 * Fortschritt, Position merken) bleibt beim aufrufenden Reader — die Lib sagt nur, welche
 * Kacheln ein gegebenes Bild hat und in welcher Reihenfolge sie anzuzeigen sind.
 */
class PanelGuide(private val source: PanelSource = GeometricPanelSource()) {

    fun guide(page: RenderedPage): PageGuide {
        val ordered = ReadingOrder.sort(source.detect(page), ReadingDirection.LEFT_TO_RIGHT)
        if (ordered.size < 2) return PageGuide(listOf(FULL_PAGE), isFullPage = true)
        return PageGuide(ordered.map { PanelGeometry.normalize(it, page.width, page.height) }, isFullPage = false)
    }

    private companion object {
        val FULL_PAGE = NormRect(0f, 0f, 1f, 1f)
    }
}
