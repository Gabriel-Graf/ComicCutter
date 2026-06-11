package com.panela.comiccutter.js

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DetectorJsTest {

    /** Zwei texturierte Kacheln (links/rechts) auf weißem Grund mit heller Mittelgasse. */
    private fun twoPanelArgb(w: Int, h: Int): IntArray {
        val px = IntArray(w * h) { 0xFFFFFFFF.toInt() } // weiß = Gutter
        fun fill(x0: Int, x1: Int) {
            for (y in 20 until h - 20) for (x in x0 until x1) {
                val on = (x / 4 + y / 4) % 2 == 0
                px[y * w + x] = if (on) 0xFF5A5A5A.toInt() else 0xFFAAAAAA.toInt()
            }
        }
        fill(20, w / 2 - 30)        // linke Kachel
        fill(w / 2 + 30, w - 20)    // rechte Kachel
        return px
    }

    @Test
    fun detektiert_zwei_panels_in_LTR_reihenfolge() {
        val w = 1000; val h = 800
        val panels = detectPanels(w, h, twoPanelArgb(w, h), rtl = false)

        assertEquals(2, panels.size)
        assertTrue(panels.all { it.left in 0.0..1.0 && it.width in 0.0..1.0 })
        val first = panels.first { it.order == 1 }
        val second = panels.first { it.order == 2 }
        assertTrue(first.left < second.left)
    }

    @Test
    fun rtl_dreht_die_reihenfolge() {
        val w = 1000; val h = 800
        val panels = detectPanels(w, h, twoPanelArgb(w, h), rtl = true)
        val first = panels.first { it.order == 1 }
        val second = panels.first { it.order == 2 }
        assertTrue(first.left > second.left)
    }
}
