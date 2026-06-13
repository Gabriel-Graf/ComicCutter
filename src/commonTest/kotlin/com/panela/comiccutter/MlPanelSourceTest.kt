package com.panela.comiccutter

import com.panela.comiccutter.model.RenderedPage
import kotlin.test.Test
import kotlin.test.assertEquals

class MlPanelSourceTest {
    /** Fake runner: returns fixed detections, ignores pixels — tests the mapping without a model. */
    private class FakeRunner(val out: List<RawDetection>) : ModelRunner {
        override fun infer(page: RenderedPage): List<RawDetection> = out
    }

    private val page = RenderedPage(100, 100, IntArray(100 * 100))

    @Test
    fun maps_runner_output_through_filter_to_panelrects() {
        val runner = FakeRunner(
            listOf(
                RawDetection(10, 10, 30, 30, 0.9f, 0),
                RawDetection(10, 10, 30, 30, 0.5f, 0),
                RawDetection(60, 60, 30, 30, 0.2f, 0),
            ),
        )
        val source = MlPanelSource(runner, MlFilter(minScore = 0.3f, nmsIoU = 0.5f, minAreaFraction = 0f))
        assertEquals(listOf(PanelRect(10, 10, 30, 30)), source.detect(page))
    }
}
