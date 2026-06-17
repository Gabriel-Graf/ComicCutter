package com.panela.comiccutter

import kotlin.test.Test
import kotlin.test.assertEquals

class MlFilterTest {
    private fun det(x: Int, y: Int, w: Int, h: Int, s: Float, c: Int = 0) =
        RawDetection(x, y, w, h, s, c)

    @Test
    fun conf_below_threshold_is_dropped() {
        val filter = MlFilter(minScore = 0.5f, nmsIoU = 1f, minAreaFraction = 0f)
        val out = filter.apply(listOf(det(0, 0, 10, 10, 0.4f), det(20, 0, 10, 10, 0.9f)), pageW = 100, pageH = 100)
        assertEquals(listOf(PanelRect(20, 0, 10, 10, 0.9f)), out)
    }

    @Test
    fun nms_suppresses_overlapping_weaker_box() {
        val filter = MlFilter(minScore = 0f, nmsIoU = 0.5f, minAreaFraction = 0f)
        val out = filter.apply(listOf(det(0, 0, 100, 100, 0.9f), det(5, 5, 100, 100, 0.6f)), pageW = 200, pageH = 200)
        assertEquals(listOf(PanelRect(0, 0, 100, 100, 0.9f)), out)
    }

    @Test
    fun box_below_min_area_is_dropped() {
        val filter = MlFilter(minScore = 0f, nmsIoU = 1f, minAreaFraction = 0.01f)
        val out = filter.apply(listOf(det(0, 0, 5, 5, 0.9f), det(50, 50, 20, 20, 0.9f)), pageW = 100, pageH = 100)
        assertEquals(listOf(PanelRect(50, 50, 20, 20, 0.9f)), out)
    }

    @Test
    fun keep_class_filters_out_other_classes() {
        val filter = MlFilter(minScore = 0f, nmsIoU = 1f, minAreaFraction = 0f, keepClass = 0)
        val out = filter.apply(listOf(det(0, 0, 10, 10, 0.9f, c = 1), det(20, 0, 10, 10, 0.9f, c = 0)), pageW = 100, pageH = 100)
        assertEquals(listOf(PanelRect(20, 0, 10, 10, 0.9f)), out)
    }

    @Test
    fun apply_carries_detection_score_into_panel() {
        val filter = MlFilter(minScore = 0.1f, nmsIoU = 0.5f, minAreaFraction = 0.0f, keepClass = null)
        val dets = listOf(RawDetection(x = 0, y = 0, width = 50, height = 50, score = 0.83f, cls = 0))
        val panels = filter.apply(dets, pageW = 100, pageH = 100)
        assertEquals(1, panels.size)
        assertEquals(0.83f, panels[0].score, 0.0001f)
    }
}
