package com.panela.comiccutter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * E2E test with a "realistic" mixed layout:
 * - A wide panel spanning the full page width at the top
 * - Two side-by-side panels below it (left + right)
 *
 * Expected LTR detection: top, then bottom-left, then bottom-right.
 */
class PanelDetectorE2ETest {

    private val detector = PanelDetector()

    @Test
    fun `mixed layout - wide top panel plus two panels below - LTR`() {
        val w = 600
        val h = 800
        val gutter = 40

        val topPanelH = 340
        val topPanel = PanelRect(0, 0, w, topPanelH)

        val bottomY = topPanelH + gutter
        val bottomH = h - bottomY
        val panelW = (w - gutter) / 2
        val bottomLeft = PanelRect(0, bottomY, panelW, bottomH)
        val bottomRight = PanelRect(panelW + gutter, bottomY, panelW, bottomH)

        val pg = SyntheticPage.of(w, h, listOf(topPanel, bottomLeft, bottomRight))
        val panels = detector.detect(pg, ReadingDirection.LEFT_TO_RIGHT)

        assertEquals(3, panels.size, "Expected exactly 3 panels (top + bottom-left + bottom-right)")

        assertTrue(
            panels[0].centerY < h / 2,
            "Panel[0] should be in the upper half, centerY=${panels[0].centerY}"
        )
        assertTrue(
            panels[1].centerY > h / 2,
            "Panel[1] should be in the lower half, centerY=${panels[1].centerY}"
        )
        assertTrue(
            panels[1].centerX < w / 2,
            "Panel[1] should be on the left (LTR), centerX=${panels[1].centerX}"
        )
        assertTrue(
            panels[2].centerY > h / 2,
            "Panel[2] should be in the lower half, centerY=${panels[2].centerY}"
        )
        assertTrue(
            panels[2].centerX > w / 2,
            "Panel[2] should be on the right (LTR), centerX=${panels[2].centerX}"
        )
    }
}
