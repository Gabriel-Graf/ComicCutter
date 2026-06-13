package com.panela.comiccutter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BorderLineSplitTest {
    private fun mask(vararg rows: String): Triple<BooleanArray, Int, Int> {
        val h = rows.size; val w = rows[0].length
        val m = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) m[y * w + x] = rows[y][x] == '#'
        return Triple(m, w, h)
    }
    private fun full(w: Int, h: Int) = PanelRect(0, 0, w, h)

    @Test
    fun `two light fields separated by a dark horizontal line yield two`() {
        val rows = Array(9) { y -> if (y == 4) "##########" else ".........." }
        val (m, w, h) = mask(*rows)
        val out = BorderLineSplit.split(m, w, h, full(w, h), minPanel = 2)
        assertEquals(2, out.size)
        assertTrue(out.all { it.height in 3..5 })
    }

    @Test
    fun `no clear line yields a single field unchanged`() {
        val rows = Array(9) { ".".repeat(10) }
        val (m, w, h) = mask(*rows)
        assertEquals(1, BorderLineSplit.split(m, w, h, full(w, h), minPanel = 2).size)
    }

    @Test
    fun `uniformly dark region is NOT split apart`() {
        val rows = Array(9) { "#".repeat(10) }
        val (m, w, h) = mask(*rows)
        assertEquals(1, BorderLineSplit.split(m, w, h, full(w, h), minPanel = 2).size)
    }

    @Test
    fun `2x2 black-bordered without white gutter yields four`() {
        val rows = Array(9) { y ->
            buildString { for (x in 0 until 11) append(if (y == 4 || x == 5) '#' else '.') }
        }
        val (m, w, h) = mask(*rows)
        val out = BorderLineSplit.split(m, w, h, full(w, h), minPanel = 2)
        assertEquals(4, out.size)
    }
}
