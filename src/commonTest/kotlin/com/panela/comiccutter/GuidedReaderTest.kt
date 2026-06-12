package com.panela.comiccutter

import com.panela.comiccutter.model.RenderedPage
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GuidedReaderTest {
    /** Zählt detect-Aufrufe je Seite → beweist Caching. Boxen kommen aus einer Map je Seite. */
    private class SpySource(val perPage: Map<Int, List<PanelRect>>) : PanelSource {
        val calls = mutableListOf<Int>()
        private var current = 0
        fun pageFor(page: Int): RenderedPage { current = page; return RenderedPage(100, 100, IntArray(1)) }
        override fun detect(page: RenderedPage): List<PanelRect> {
            calls.add(current); return perPage[current] ?: emptyList()
        }
    }

    private fun reader(spy: SpySource, pages: Int) =
        GuidedReader(spy, pageCount = pages, loadPage = { spy.pageFor(it) })

    @Test
    fun next_geht_durch_panels_einer_seite_in_LTR() = runTest {
        val spy = SpySource(mapOf(0 to listOf(PanelRect(60, 10, 30, 30), PanelRect(10, 10, 30, 30))))
        val r = reader(spy, pages = 1)
        assertEquals(GuidedStep(0, PanelGeometry.normalize(PanelRect(10, 10, 30, 30), 100, 100), false), r.start())
        assertEquals(GuidedStep(0, PanelGeometry.normalize(PanelRect(60, 10, 30, 30), 100, 100), false), r.next())
        assertNull(r.next())
    }

    @Test
    fun next_ueberschreitet_seitengrenze() = runTest {
        val spy = SpySource(
            mapOf(
                0 to listOf(PanelRect(10, 10, 30, 30), PanelRect(60, 10, 30, 30)),
                1 to listOf(PanelRect(10, 10, 30, 30), PanelRect(60, 10, 30, 30)),
            ),
        )
        val r = reader(spy, pages = 2)
        r.start(); r.next()                       // letztes Panel Seite 0
        assertEquals(1, r.next()!!.page)          // → Seite 1
        assertEquals(0, r.position().unit)        // erste Einheit der neuen Seite
    }

    @Test
    fun previous_geht_ueber_seitengrenze_zurueck() = runTest {
        val spy = SpySource(
            mapOf(
                0 to listOf(PanelRect(10, 10, 30, 30), PanelRect(60, 10, 30, 30)),
                1 to listOf(PanelRect(10, 10, 30, 30), PanelRect(60, 10, 30, 30)),
            ),
        )
        val r = reader(spy, pages = 2)
        r.start(); r.next(); r.next()             // Seite 1, Einheit 0
        assertEquals(1, r.position().page)
        val back = r.previous()!!                 // zurück auf Seite 0, letzte Einheit
        assertEquals(0, back.page)
        assertEquals(1, r.position().unit)
        assertEquals(GuidedStep(0, PanelGeometry.normalize(PanelRect(10, 10, 30, 30), 100, 100), false), r.previous())  // page0 unit0
        assertNull(r.previous())   // schon am Anfang
    }

    @Test
    fun source_wird_pro_seite_nur_einmal_gerufen() = runTest {
        val spy = SpySource(mapOf(0 to listOf(PanelRect(10, 10, 30, 30), PanelRect(60, 10, 30, 30))))
        val r = reader(spy, pages = 1)
        r.start(); r.next(); r.previous(); r.next()
        assertEquals(listOf(0), spy.calls)        // genau ein detect für Seite 0
    }

    @Test
    fun seite_mit_unter_2_panels_faellt_auf_vollseite_zurueck() = runTest {
        val spy = SpySource(mapOf(0 to listOf(PanelRect(10, 10, 80, 80))))
        val r = reader(spy, pages = 1)
        assertEquals(GuidedStep(0, NormRect(0f, 0f, 1f, 1f), true), r.start())
        assertNull(r.next())
    }

    @Test
    fun leere_seite_ist_eine_vollseiten_einheit() = runTest {
        val spy = SpySource(mapOf(0 to emptyList()))
        val r = reader(spy, pages = 1)
        assertEquals(GuidedStep(0, NormRect(0f, 0f, 1f, 1f), true), r.start())
        assertNull(r.next())
    }

    @Test
    fun next_ohne_vorheriges_start_wirft_nicht() = runTest {
        val spy = SpySource(mapOf(0 to listOf(PanelRect(10, 10, 30, 30), PanelRect(60, 10, 30, 30))))
        val r = reader(spy, pages = 1)
        val step = r.next()          // ohne start() — darf nicht werfen
        assertEquals(0, step!!.page) // bleibt auf Seite 0
        // Ohne start() steht pos auf (0,0); next() rückt regulär auf Einheit 1 vor —
        // Einheit 0 wird übersprungen. start() ist der Weg, die erste Einheit zu sehen.
        assertEquals(1, r.position().unit)
    }
}
