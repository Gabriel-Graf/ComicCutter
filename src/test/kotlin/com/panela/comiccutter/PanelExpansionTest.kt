package com.panela.comiccutter

import kotlin.test.Test
import kotlin.test.assertTrue

class PanelExpansionTest {
    @Test fun `zwei Panels dehnen in die Gutter ohne zu überlappen`() {
        val pageW = 100; val pageH = 100
        val a = PanelRect(10, 10, 30, 80)
        val b = PanelRect(60, 10, 30, 80)
        val out = PanelExpansion.expand(listOf(a, b), pageW, pageH, maxStep = 50)
        val ea = out[0]; val eb = out[1]
        assertTrue(ea.x + ea.width <= eb.x, "Panels dürfen nach Expansion nicht überlappen")
        assertTrue(ea.width > a.width || eb.width > b.width, "Erwarte Expansion in die Gasse")
    }

    @Test fun `Expansion bleibt in Seitengrenzen`() {
        val out = PanelExpansion.expand(listOf(PanelRect(5, 5, 20, 20)), 100, 100, maxStep = 50)
        val e = out[0]
        assertTrue(e.x >= 0 && e.y >= 0 && e.x + e.width <= 100 && e.y + e.height <= 100)
    }
}
