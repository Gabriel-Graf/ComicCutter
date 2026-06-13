package com.panela.comiccutter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PanelDetectorTest {
    private val det = PanelDetector()

    @Test
    fun `clean 3x2 grid yields 6 panels in LTR order`() {
        val panels = mutableListOf<PanelRect>()
        val pw = 300; val ph = 370; val gx = 20; val gy = 20; val m = 20
        for (row in 0..1) for (col in 0..2) {
            panels.add(PanelRect(m + col * (pw + gx), m + row * (ph + gy), pw, ph))
        }
        val page = SyntheticPage.of(1000, 800, panels)
        val out = det.detect(page, ReadingDirection.LEFT_TO_RIGHT)
        assertEquals(6, out.size, "Expected 6 panels, was ${out.size}")
        assertTrue(out[0].x < out[1].x && out[1].x < out[2].x)
        assertTrue(out[0].y < out[3].y)
    }

    @Test
    fun `speech bubble inside panel stays one panel`() {
        val panel = PanelRect(50, 50, 900, 700)
        val bubble = PanelRect(400, 300, 200, 150)
        val page = SyntheticPage.of(1000, 800, listOf(panel), holes = listOf(bubble))
        val out = det.detect(page, ReadingDirection.LEFT_TO_RIGHT)
        assertEquals(1, out.size)
    }

    @Test
    fun `full bleed to the edge is preserved`() {
        val left = PanelRect(0, 0, 460, 800)
        val right = PanelRect(500, 20, 480, 760)
        val page = SyntheticPage.of(1000, 800, listOf(left, right))
        val out = det.detect(page, ReadingDirection.LEFT_TO_RIGHT)
        assertEquals(2, out.size)
    }

    @Test
    fun `blank page yields no panels`() {
        val page = SyntheticPage.of(1000, 800, emptyList())
        assertEquals(0, det.detect(page, ReadingDirection.LEFT_TO_RIGHT).size)
    }

    @Test
    fun `single full-page panel yields exactly one panel`() {
        val page = SyntheticPage.of(1000, 800, listOf(PanelRect(20, 20, 960, 760)))
        assertEquals(1, det.detect(page, ReadingDirection.LEFT_TO_RIGHT).size)
    }

    @Test
    fun `tiny speck below min area is discarded`() {
        val panel = PanelRect(40, 40, 900, 700)
        val speck = PanelRect(10, 10, 8, 8)
        val page = SyntheticPage.of(1000, 800, listOf(panel, speck))
        assertEquals(1, det.detect(page, ReadingDirection.LEFT_TO_RIGHT).size)
    }

    @Test
    fun `art protrudes into the gutter - panels still separate`() {
        // Two columns with a 30px gutter (x=460..489). A protrusion from the left panel
        // reaches into the gutter up to x=474, but does NOT block it entirely (white remains x=475..489).
        // The earlier XY-cut would have failed on the ink in this column; the flood fill
        // still finds a white passage and separates them.
        val left = PanelRect(20, 20, 440, 760)        // 20..459
        val right = PanelRect(490, 20, 490, 760)       // 490..979
        val intrusion = PanelRect(460, 380, 15, 40)    // protrudes into the gutter, attached to the left panel
        val page = SyntheticPage.of(1000, 800, listOf(left, right, intrusion))
        val out = det.detect(page, ReadingDirection.LEFT_TO_RIGHT)
        assertEquals(2, out.size, "Expected 2 separate panels despite the gutter protrusion, was ${out.size}")
    }

    @Test
    fun `Otsu segments even with a non-white background`() {
        // Light gray page background instead of pure white: a fixed threshold (128) would still
        // work, but this checks that Otsu handles the separation adaptively.
        val panels = listOf(PanelRect(20, 20, 440, 760), PanelRect(500, 20, 480, 760))
        val page = SyntheticPage.of(1000, 800, panels, bg = 0xFFD0D0D0.toInt())
        val out = det.detect(page, ReadingDirection.LEFT_TO_RIGHT)
        assertEquals(2, out.size, "Expected 2 panels on a gray background, was ${out.size}")
    }

    @Test
    fun `black-bordered 2x2 without white gutter yields 4 panels`() {
        val px = IntArray(1000 * 800) { 0xFFFFFFFF.toInt() }
        fun vline(x: Int) { for (y in 0 until 800) for (dx in 0..5) px[y * 1000 + (x + dx)] = 0xFF101010.toInt() }
        fun hline(y: Int) { for (x in 0 until 1000) for (dy in 0..5) px[(y + dy) * 1000 + x] = 0xFF101010.toInt() }
        hline(10); hline(395); hline(784); vline(10); vline(495); vline(984)
        for (q in 0..1) for (r in 0..1) {
            val ox = 60 + r * 485; val oy = 60 + q * 385
            for (y in oy until oy + 250) for (x in ox until ox + 350) px[y * 1000 + x] = 0xFF808080.toInt()
        }
        val page = com.panela.comiccutter.model.RenderedPage(1000, 800, px)
        val out = PanelDetector().detect(page, ReadingDirection.LEFT_TO_RIGHT)
        assertEquals(4, out.size, "Expected 4 panels (border split), was ${out.size}")
    }

    @Test
    fun `speech bubble is not counted as its own panel`() {
        val panel = PanelRect(50, 50, 900, 700)
        val px = IntArray(1000 * 800) { 0xFFFFFFFF.toInt() }
        for (y in panel.y until panel.y + panel.height) for (x in panel.x until panel.x + panel.width)
            px[y * 1000 + x] = 0xFF303030.toInt()
        for (y in 300 until 450) for (x in 400 until 600) px[y * 1000 + x] = 0xFFFFFFFF.toInt()
        for (y in 360 until 380) for (x in 440 until 560) px[y * 1000 + x] = 0xFF101010.toInt()
        val page = com.panela.comiccutter.model.RenderedPage(1000, 800, px)
        val out = PanelDetector().detect(page, ReadingDirection.LEFT_TO_RIGHT)
        assertEquals(1, out.size, "Bubble must not be its own panel, was ${out.size}")
    }

    @Test
    fun `solid SFX blob is discarded, bright panel stays`() {
        val px = IntArray(1000 * 800) { 0xFFFFFFFF.toInt() }
        // Bright, bordered panel (dark border, bright interior + some content) -> low dark fraction
        for (y in 50 until 450) for (x in 50 until 450) {
            val border = x < 56 || x >= 444 || y < 56 || y >= 444
            if (border) px[y * 1000 + x] = 0xFF101010.toInt()
        }
        for (y in 100 until 160) for (x in 100 until 300) px[y * 1000 + x] = 0xFF202020.toInt() // little content
        // Solid black SFX blob (small, almost fully dark)
        for (y in 600 until 720) for (x in 600 until 740) px[y * 1000 + x] = 0xFF050505.toInt()
        val page = com.panela.comiccutter.model.RenderedPage(1000, 800, px)
        val out = PanelDetector().detect(page, ReadingDirection.LEFT_TO_RIGHT)
        assertEquals(1, out.size, "Expected only the bright panel (SFX blob discarded), was ${out.size}")
        assertTrue(out[0].x < 460 && out[0].y < 460, "The remaining panel is the bordered top-left one")
    }

}
