package com.panela.comiccutter

import com.panela.comiccutter.model.RenderedPage

/** Guided display steps for ONE page image. [steps] is never empty (at least one step). */
data class PageGuide(
    /** Ordered crop rects in image-normalized coordinates (0..1), comics: left-to-right. */
    val steps: List<NormRect>,
    /** true if the whole page is a single step (fallback when fewer than 2 tiles are detected). */
    val isFullPage: Boolean,
)

/**
 * Turns a page image into guided display steps: detects the tiles via the [source],
 * sorts them into reading order (comics: left-to-right) and normalizes them to [0..1].
 * With fewer than 2 tiles (splash or uncertain detection) the whole page is a single step.
 *
 * A pure image-to-tiles function with no state: page management (which page, jump,
 * progress, remembering the position) stays with the calling reader — the lib only reports which
 * tiles a given image has and in what order they should be displayed.
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
