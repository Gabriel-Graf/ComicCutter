package com.panela.comiccutter

import kotlin.test.Test
import kotlin.test.assertEquals

class MlFilterTest {
    private fun det(x: Int, y: Int, w: Int, h: Int, s: Float, c: Int = 0) =
        RawDetection(x, y, w, h, s, c)

    @Test
    fun conf_unter_schwelle_faellt_raus() {
        val filter = MlFilter(minScore = 0.5f, nmsIoU = 1f, minAreaFraction = 0f)
        val out = filter.apply(listOf(det(0, 0, 10, 10, 0.4f), det(20, 0, 10, 10, 0.9f)), pageW = 100, pageH = 100)
        assertEquals(listOf(PanelRect(20, 0, 10, 10)), out)
    }

    @Test
    fun nms_unterdrueckt_ueberlappende_schwaechere_box() {
        val filter = MlFilter(minScore = 0f, nmsIoU = 0.5f, minAreaFraction = 0f)
        val out = filter.apply(listOf(det(0, 0, 100, 100, 0.9f), det(5, 5, 100, 100, 0.6f)), pageW = 200, pageH = 200)
        assertEquals(listOf(PanelRect(0, 0, 100, 100)), out)
    }

    @Test
    fun box_unter_min_area_faellt_raus() {
        val filter = MlFilter(minScore = 0f, nmsIoU = 1f, minAreaFraction = 0.01f)
        val out = filter.apply(listOf(det(0, 0, 5, 5, 0.9f), det(50, 50, 20, 20, 0.9f)), pageW = 100, pageH = 100)
        assertEquals(listOf(PanelRect(50, 50, 20, 20)), out)
    }

    @Test
    fun keep_class_filtert_andere_klassen() {
        val filter = MlFilter(minScore = 0f, nmsIoU = 1f, minAreaFraction = 0f, keepClass = 0)
        val out = filter.apply(listOf(det(0, 0, 10, 10, 0.9f, c = 1), det(20, 0, 10, 10, 0.9f, c = 0)), pageW = 100, pageH = 100)
        assertEquals(listOf(PanelRect(20, 0, 10, 10)), out)
    }
}
