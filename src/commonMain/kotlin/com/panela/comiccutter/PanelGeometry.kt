package com.panela.comiccutter

/** Panel in image-normalized coordinates [0..1] relative to the page. */
data class NormRect(val left: Float, val top: Float, val width: Float, val height: Float, val score: Float = 1.0f) {
    val centerX: Float get() = left + width / 2f
    val centerY: Float get() = top + height / 2f
    fun contains(x: Float, y: Float): Boolean =
        x >= left && x < left + width && y >= top && y < top + height
}

/**
 * Pure geometry for the comic reader: normalize panel coordinates,
 * resolve tap hits, compute the zoom factor. No Android, no viewport knowledge —
 * the Compose layer converts viewport taps into image-normalized coordinates.
 */
object PanelGeometry {

    fun normalize(panel: PanelRect, pageW: Int, pageH: Int): NormRect =
        NormRect(
            left = panel.x.toFloat() / pageW,
            top = panel.y.toFloat() / pageH,
            width = panel.width.toFloat() / pageW,
            height = panel.height.toFloat() / pageH,
            score = panel.score,
        )

    /** Index of the panel that contains the (normalized) point, otherwise null (gutter/margin). */
    fun hitTest(xNorm: Float, yNorm: Float, panels: List<NormRect>): Int? {
        val i = panels.indexOfFirst { it.contains(xNorm, yNorm) }
        return if (i >= 0) i else null
    }

    /** Largest normalized area fraction (w*h) among the panels; 0 if empty. */
    fun maxAreaFraction(panels: List<NormRect>): Float =
        panels.maxOfOrNull { it.width * it.height } ?: 0f

    /**
     * Scale factor at which [panel] (image-normalized) fills the viewport, taking into account
     * the content rectangle ([contentW]x[contentH]) shown under ContentScale.Fit within the
     * viewport ([viewportW]x[viewportH]). Pivot = panel center.
     *
     * **Contain** (`min(sx,sy)`): the whole panel stays visible, it is NEVER cropped —
     * the zoom does not overshoot the panel. A page-wide/-tall panel already fills its limiting
     * axis, so there a factor of ≈ 1 is the correct (crop-free) maximum.
     */
    fun fitScale(
        panel: NormRect,
        contentW: Float, contentH: Float,
        viewportW: Float, viewportH: Float,
        marginFraction: Float,
    ): Float {
        val panelW = (panel.width * contentW).coerceAtLeast(1f)
        val panelH = (panel.height * contentH).coerceAtLeast(1f)
        val sx = viewportW / panelW
        val sy = viewportH / panelH
        return (1f - 2f * marginFraction) * minOf(sx, sy)
    }
}
