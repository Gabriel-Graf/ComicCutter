package com.panela.comiccutter

import com.panela.comiccutter.model.RenderedPage
import kotlin.test.Test
import kotlin.test.assertEquals

class GeometricPanelSourceTest {
    /** A 2-panel page (white gutter in the middle): the source returns the same boxes as the detector. */
    @Test
    fun source_returns_same_boxes_as_detector() {
        val w = 200; val h = 100
        val px = IntArray(w * h) { 0xFFFFFFFF.toInt() }
        for (y in 10 until 90) for (x in 0 until w) {
            val gutter = x in 95..104
            if (!gutter) px[y * w + x] = 0xFF202020.toInt()
        }
        val page = RenderedPage(w, h, px)
        val viaDetector = PanelDetector().detect(page, ReadingDirection.LEFT_TO_RIGHT)
        val viaSource = GeometricPanelSource().detect(page)
        assertEquals(viaDetector, viaSource)
    }
}
