package com.panela.comiccutter

import com.panela.comiccutter.model.RenderedPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PanelGuideTest {
    private val page = RenderedPage(100, 100, IntArray(1))

    @Test
    fun orders_and_normalizes_tiles_LTR() {
        // right (x=60) inserted first, left (x=10) after → LTR sorts left first
        val source = PanelSource { listOf(PanelRect(60, 10, 30, 30), PanelRect(10, 10, 30, 30)) }
        val g = PanelGuide(source).guide(page)
        assertFalse(g.isFullPage)
        assertEquals(
            listOf(
                PanelGeometry.normalize(PanelRect(10, 10, 30, 30), 100, 100),
                PanelGeometry.normalize(PanelRect(60, 10, 30, 30), 100, 100),
            ),
            g.steps,
        )
    }

    @Test
    fun fewer_than_2_tiles_is_full_page() {
        val source = PanelSource { listOf(PanelRect(10, 10, 80, 80)) }
        val g = PanelGuide(source).guide(page)
        assertTrue(g.isFullPage)
        assertEquals(listOf(NormRect(0f, 0f, 1f, 1f)), g.steps)
    }

    @Test
    fun empty_page_is_full_page() {
        val source = PanelSource { emptyList() }
        val g = PanelGuide(source).guide(page)
        assertTrue(g.isFullPage)
        assertEquals(listOf(NormRect(0f, 0f, 1f, 1f)), g.steps)
    }
}
