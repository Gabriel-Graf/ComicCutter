package com.panela.comiccutter

import com.panela.comiccutter.model.RenderedPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PanelGuideTest {
    private val page = RenderedPage(100, 100, IntArray(1))

    @Test
    fun ordnet_und_normalisiert_kacheln_LTR() {
        // rechts (x=60) zuerst eingefügt, links (x=10) danach → LTR sortiert links zuerst
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
    fun unter_2_kacheln_ist_vollseite() {
        val source = PanelSource { listOf(PanelRect(10, 10, 80, 80)) }
        val g = PanelGuide(source).guide(page)
        assertTrue(g.isFullPage)
        assertEquals(listOf(NormRect(0f, 0f, 1f, 1f)), g.steps)
    }

    @Test
    fun leere_seite_ist_vollseite() {
        val source = PanelSource { emptyList() }
        val g = PanelGuide(source).guide(page)
        assertTrue(g.isFullPage)
        assertEquals(listOf(NormRect(0f, 0f, 1f, 1f)), g.steps)
    }
}
