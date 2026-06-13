package com.panela.comiccutter

import kotlin.test.Test
import kotlin.test.assertTrue

class PanelExpansionTest {
    @Test fun `two panels expand into the gutter without overlapping`() {
        val pageW = 100; val pageH = 100
        val a = PanelRect(10, 10, 30, 80)
        val b = PanelRect(60, 10, 30, 80)
        val out = PanelExpansion.expand(listOf(a, b), pageW, pageH, maxStep = 50)
        val ea = out[0]; val eb = out[1]
        assertTrue(ea.x + ea.width <= eb.x, "Panels must not overlap after expansion")
        assertTrue(ea.width > a.width || eb.width > b.width, "Expected expansion into the gutter")
    }

    @Test fun `expansion stays within page bounds`() {
        val out = PanelExpansion.expand(listOf(PanelRect(5, 5, 20, 20)), 100, 100, maxStep = 50)
        val e = out[0]
        assertTrue(e.x >= 0 && e.y >= 0 && e.x + e.width <= 100 && e.y + e.height <= 100)
    }

    @Test fun `default maxStep does not collapse panels (Int overflow regression)`() {
        // Without maxStep (default Int.MAX_VALUE): pr + maxStep must not overflow.
        val a = PanelRect(10, 10, 30, 80)
        val b = PanelRect(60, 10, 30, 80)
        val out = PanelExpansion.expand(listOf(a, b), 100, 100)
        assertTrue(out.all { it.width > 1 && it.height > 1 }, "Panels must not collapse to 1px with default maxStep: $out")
        assertTrue(out[0].x + out[0].width <= out[1].x, "no overlap with the default either")
    }
}
