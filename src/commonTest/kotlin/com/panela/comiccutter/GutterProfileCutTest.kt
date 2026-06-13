package com.panela.comiccutter

import com.panela.comiccutter.model.RenderedPage
import kotlin.test.Test
import kotlin.test.assertTrue

class GutterProfileCutTest {

    /**
     * Page with a [bg] background; each panel is filled with deterministic checkerboard noise
     * (high luminance variance = real artwork). A solid fill would be indistinguishable from
     * a gutter, hence the pattern. The midtones (90/170) sit deliberately between the dark and
     * light gutter thresholds so that the content trim recognizes the artwork as real image content.
     */
    private fun texturedPage(w: Int, h: Int, panels: List<PanelRect>, bg: Int): RenderedPage {
        val px = IntArray(w * h) { bg }
        for (p in panels) {
            for (y in p.y until p.y + p.height) for (x in p.x until p.x + p.width) {
                if (x in 0 until w && y in 0 until h) {
                    val on = (x / 4 + y / 4) % 2 == 0
                    px[y * w + x] = if (on) 0xFF5A5A5A.toInt() else 0xFFAAAAAA.toInt()
                }
            }
        }
        return RenderedPage(w, h, px)
    }

    private fun matches(boxes: List<PanelRect>, target: PanelRect, tol: Int = 14): Boolean =
        boxes.any {
            kotlin.math.abs(it.x - target.x) <= tol && kotlin.math.abs(it.y - target.y) <= tol &&
                kotlin.math.abs(it.width - target.width) <= tol && kotlin.math.abs(it.height - target.height) <= tol
        }

    @Test
    fun `white gutters separate two side-by-side panels`() {
        val left = PanelRect(20, 20, 440, 760)
        val right = PanelRect(540, 20, 440, 760)
        val page = texturedPage(1000, 800, listOf(left, right), bg = 0xFFFFFFFF.toInt())

        val panels = GutterProfileCut.detect(page)

        assertTrue(panels.size >= 2, "expected >=2 panels, was ${panels.size}")
        assertTrue(matches(panels, left), "left panel not found: $panels")
        assertTrue(matches(panels, right), "right panel not found: $panels")
    }

    @Test
    fun `dark gutters (dark-on-dark) separate stacked panels`() {
        val top = PanelRect(20, 20, 960, 360)
        val bottom = PanelRect(20, 440, 960, 340)
        // Black background = dark gutter; a flood fill would separate nothing here.
        val page = texturedPage(1000, 800, listOf(top, bottom), bg = 0xFF050505.toInt())

        val panels = GutterProfileCut.detect(page)

        assertTrue(panels.size >= 2, "expected >=2 panels with a dark gutter, was ${panels.size}")
        assertTrue(matches(panels, top), "top panel not found: $panels")
        assertTrue(matches(panels, bottom), "bottom panel not found: $panels")
    }

    @Test
    fun `2x2 grid is cut recursively into four panels`() {
        val tl = PanelRect(20, 20, 440, 360)
        val tr = PanelRect(540, 20, 440, 360)
        val bl = PanelRect(20, 440, 440, 340)
        val br = PanelRect(540, 440, 440, 340)
        val page = texturedPage(1000, 800, listOf(tl, tr, bl, br), bg = 0xFFFFFFFF.toInt())

        val panels = GutterProfileCut.detect(page)

        assertTrue(panels.size >= 4, "expected >=4 panels (recursive cut on both axes), was ${panels.size}")
        listOf(tl, tr, bl, br).forEach { assertTrue(matches(panels, it), "panel $it not found: $panels") }
    }

    @Test
    fun `speech bubble crossing the gutter does not prevent separation`() {
        val left = PanelRect(20, 20, 440, 760)
        val right = PanelRect(540, 20, 440, 760)
        // The speech bubble extends across the gutter (x 460..540) within a narrow height band — the
        // edge density of the gutter columns stays low over the full height (tolerant).
        val bubble = PanelRect(460, 360, 80, 80)
        val page = texturedPage(1000, 800, listOf(left, right, bubble), bg = 0xFFFFFFFF.toInt())

        val panels = GutterProfileCut.detect(page)

        // The key point: despite the crossing bubble, the cut still finds two side-by-side
        // panels (the bubble may shift the dividing line, but not remove it).
        assertTrue(panels.size >= 2, "crossing bubble prevented separation: $panels")
        assertTrue(panels.any { it.centerX < 500 }, "no left panel: $panels")
        assertTrue(panels.any { it.centerX > 500 }, "no right panel: $panels")
    }

    @Test
    fun `empty page returns no panels`() {
        val page = RenderedPage(400, 400, IntArray(400 * 400) { 0xFF808080.toInt() })
        assertTrue(GutterProfileCut.detect(page).isEmpty())
    }
}
