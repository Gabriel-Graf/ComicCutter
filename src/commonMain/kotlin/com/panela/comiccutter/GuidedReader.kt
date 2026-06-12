package com.panela.comiccutter

import com.panela.comiccutter.model.RenderedPage

/** Ein Schritt im geführten Lesefluss: Ziel-Rechteck einer Seite (bild-normalisiert). */
data class GuidedStep(
    val page: Int,
    val rect: NormRect,
    val isFullPage: Boolean,
)

/**
 * Geführte Panel-für-Panel-Navigation über Seiten. Besitzt Panel-Cache (Quelle je Seite
 * nur einmal), Lese-Position, Crop-Rect (normalisierte Panel-Box) und Confidence-Fallback
 * (<2 Panels → Vollseite). Comics: Reihenfolge fix links→rechts.
 */
class GuidedReader(
    private val source: PanelSource,
    private val pageCount: Int,
    private val loadPage: suspend (Int) -> RenderedPage,
) {
    private val cache = mutableMapOf<Int, List<PanelRect>>()
    private val dims = mutableMapOf<Int, Pair<Int, Int>>()
    private var pos = GuidedPosition(0, 0)

    suspend fun start(): GuidedStep { pos = GuidedPosition(0, 0); return stepAt(pos) }

    suspend fun next(): GuidedStep? {
        val n = GuidedNavigator.next(pos, pageCount) { unitsAt(it) } ?: return null
        pos = n; return stepAt(pos)
    }

    suspend fun previous(): GuidedStep? {
        if (pos.unit == 0 && pos.page > 0) panels(pos.page - 1)  // prevPage-units müssen im Cache sein
        val p = GuidedNavigator.previous(pos, pageCount) { unitsAt(it) } ?: return null
        pos = p; return stepAt(pos)
    }

    /** Ganze Seite als Einheit (z. B. wenn die App die Vollseite zeigen will). Ändert die Position nicht. */
    suspend fun fullPage(page: Int): GuidedStep {
        panels(page)
        return GuidedStep(page, NormRect(0f, 0f, 1f, 1f), isFullPage = true)
    }

    fun position(): GuidedPosition = pos

    private suspend fun stepAt(p: GuidedPosition): GuidedStep {
        val boxes = panels(p.page)
        val (w, h) = dims.getValue(p.page)
        if (boxes.size < 2) return GuidedStep(p.page, NormRect(0f, 0f, 1f, 1f), isFullPage = true)
        return GuidedStep(p.page, PanelGeometry.normalize(boxes[p.unit], w, h), isFullPage = false)
    }

    private fun unitsAt(page: Int): Int = cache[page]?.size?.coerceAtLeast(1)
        ?: error("Seite $page nicht geladen — panels() vor unitsAt aufrufen")

    /** Lädt + erkennt + sortiert Panels einer Seite genau einmal (Cache). */
    private suspend fun panels(page: Int): List<PanelRect> = cache.getOrPut(page) {
        val rendered = loadPage(page)
        dims[page] = rendered.width to rendered.height
        ReadingOrder.sort(source.detect(rendered), ReadingDirection.LEFT_TO_RIGHT)
    }
}
