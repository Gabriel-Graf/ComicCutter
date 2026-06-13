package com.panela.comiccutter

import com.panela.comiccutter.model.RenderedPage

/** Builds synthetic comic pages as RenderedPage for detector tests. */
object SyntheticPage {
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val BLACK = 0xFF101010.toInt()

    /**
     * White page [w]x[h]; each rectangle in [panels] is filled dark (panel content).
     * [holes] are then filled white again (e.g. speech-bubble islands).
     */
    fun of(w: Int, h: Int, panels: List<PanelRect>, holes: List<PanelRect> = emptyList(), bg: Int = 0xFFFFFFFF.toInt()): RenderedPage {
        val px = IntArray(w * h) { bg }
        fun fill(r: PanelRect, color: Int) {
            for (y in r.y until r.y + r.height) for (x in r.x until r.x + r.width) {
                if (x in 0 until w && y in 0 until h) px[y * w + x] = color
            }
        }
        panels.forEach { fill(it, BLACK) }
        holes.forEach { fill(it, bg) }
        return RenderedPage(w, h, px)
    }
}
