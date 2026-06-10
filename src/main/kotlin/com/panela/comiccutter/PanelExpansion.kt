package com.panela.comiccutter

/**
 * Dehnt jedes Panel kantenweise nach außen (in die Gutter), bis zur Mitte der Gutter zum nächsten
 * Nachbar-Panel (so dass sich zwei expandierende Panels exakt treffen, nie überlappen) oder bis zum
 * Seitenrand, wenn auf der Seite kein Nachbar liegt. Analog Kumikos Panel-Expansion, opt-in.
 */
object PanelExpansion {
    fun expand(panels: List<PanelRect>, pageW: Int, pageH: Int, maxStep: Int = Int.MAX_VALUE): List<PanelRect> {
        return panels.mapIndexed { i, p ->
            val pl = p.x; val pt = p.y; val pr = p.x + p.width; val pb = p.y + p.height
            val others = panels.filterIndexed { j, _ -> j != i }
            // maxStep-Cap in Long, sonst läuft pr + Int.MAX_VALUE (Default) über und kollabiert das Panel.
            val rNb = others.filter { overlap(pt, pb, it.y, it.y + it.height) && it.x >= pr }.minOfOrNull { it.x }
            val right = (rNb?.let { pr + (it - pr) / 2 } ?: pageW).let { minOf(it.toLong(), pr.toLong() + maxStep).toInt() }
            val lNb = others.filter { overlap(pt, pb, it.y, it.y + it.height) && it.x + it.width <= pl }.maxOfOrNull { it.x + it.width }
            val left = (lNb?.let { pl - (pl - it) / 2 } ?: 0).let { maxOf(it.toLong(), pl.toLong() - maxStep).toInt() }
            val bNb = others.filter { overlap(pl, pr, it.x, it.x + it.width) && it.y >= pb }.minOfOrNull { it.y }
            val bottom = (bNb?.let { pb + (it - pb) / 2 } ?: pageH).let { minOf(it.toLong(), pb.toLong() + maxStep).toInt() }
            val tNb = others.filter { overlap(pl, pr, it.x, it.x + it.width) && it.y + it.height <= pt }.maxOfOrNull { it.y + it.height }
            val top = (tNb?.let { pt - (pt - it) / 2 } ?: 0).let { maxOf(it.toLong(), pt.toLong() - maxStep).toInt() }
            PanelRect(left, top, (right - left).coerceAtLeast(1), (bottom - top).coerceAtLeast(1))
        }
    }

    private fun overlap(a0: Int, a1: Int, b0: Int, b1: Int) = a0 < b1 && b0 < a1
}
