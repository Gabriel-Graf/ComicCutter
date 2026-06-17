package com.panela.comiccutter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PanelGeometryTest {

    // Page 1000x800; top-left panel 0..400 x 0..400
    private val panel = PanelRect(0, 0, 400, 400)

    @Test
    fun `normalize converts detection pixels into 0 to 1`() {
        val n = PanelGeometry.normalize(panel, pageW = 1000, pageH = 800)
        assertEquals(0f, n.left); assertEquals(0f, n.top)
        assertEquals(0.4f, n.width); assertEquals(0.5f, n.height)
    }

    @Test
    fun `hitTest hits the panel that contains the point`() {
        val a = PanelGeometry.normalize(PanelRect(0, 0, 400, 800), 1000, 800)   // left column
        val b = PanelGeometry.normalize(PanelRect(600, 0, 400, 800), 1000, 800) // right column
        // Point at x=0.1,y=0.5 lies in a (left)
        assertEquals(0, PanelGeometry.hitTest(0.1f, 0.5f, listOf(a, b)))
        // Point at x=0.8 lies in b (right)
        assertEquals(1, PanelGeometry.hitTest(0.8f, 0.5f, listOf(a, b)))
    }

    @Test
    fun `hitTest in the gutter hits no panel`() {
        val a = PanelGeometry.normalize(PanelRect(0, 0, 400, 800), 1000, 800)
        val b = PanelGeometry.normalize(PanelRect(600, 0, 400, 800), 1000, 800)
        // x=0.5 lies in the gutter between 0.4 and 0.6
        assertNull(PanelGeometry.hitTest(0.5f, 0.5f, listOf(a, b)))
    }

    @Test
    fun `maxAreaFraction returns the largest area fraction`() {
        val a = NormRect(0f, 0f, 0.3f, 0.4f)
        val b = NormRect(0.5f, 0.5f, 0.4f, 0.5f)
        assertTrue(kotlin.math.abs(PanelGeometry.maxAreaFraction(listOf(a, b)) - 0.20f) < 1e-4f)
        assertEquals(0f, PanelGeometry.maxAreaFraction(emptyList()))
    }

    @Test
    fun `fitScale fills the panel in the viewport with margin`() {
        val panel = NormRect(0f, 0f, 0.5f, 0.5f)
        val s = PanelGeometry.fitScale(panel, contentW = 1000f, contentH = 1500f, viewportW = 1000f, viewportH = 1500f, marginFraction = 0.05f)
        assertTrue(kotlin.math.abs(s - 1.8f) < 1e-3f, "was $s")
    }

    @Test
    fun `fitScale accounts for letterbox (narrower content rectangle)`() {
        // Wide panel (1.0x0.25) in 800px content: width-limited → contain = 1000/800 = 1.25.
        // No crop → that's the correct maximum (panel fills the width, height letterboxed).
        val panel = NormRect(0f, 0f, 1.0f, 0.25f)
        val s = PanelGeometry.fitScale(panel, contentW = 800f, contentH = 1500f, viewportW = 1000f, viewportH = 1500f, marginFraction = 0f)
        assertTrue(kotlin.math.abs(s - 1.25f) < 1e-3f, "was $s")
    }

    @Test
    fun `fitScale never crops the panel (zoom fits both axes)`() {
        // Very wide panel: the zoom must not exceed the panel width, otherwise it crops.
        // Expects contain (1.25), NOT cover (4.0).
        val panel = NormRect(0f, 0f, 1.0f, 0.25f)
        val s = PanelGeometry.fitScale(panel, contentW = 800f, contentH = 1500f, viewportW = 1000f, viewportH = 1500f, marginFraction = 0f)
        // The scaled panel must not exceed the viewport on either axis:
        val scaledW = s * 1.0f * 800f
        val scaledH = s * 0.25f * 1500f
        assertTrue(scaledW <= 1000f + 1e-3f && scaledH <= 1500f + 1e-3f, "Crop! w=$scaledW h=$scaledH")
    }

    @Test
    fun normalize_carries_score() {
        val panel = PanelRect(x = 10, y = 20, width = 30, height = 40, score = 0.66f)
        val norm = PanelGeometry.normalize(panel, pageW = 100, pageH = 200)
        assertEquals(0.66f, norm.score, 0.0001f)
    }
}
