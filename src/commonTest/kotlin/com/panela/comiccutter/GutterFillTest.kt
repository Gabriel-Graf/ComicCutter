package com.panela.comiccutter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GutterFillTest {
    private fun mask(vararg rows: String): Triple<BooleanArray, Int, Int> {
        val h = rows.size; val w = rows[0].length
        val m = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) m[y * w + x] = rows[y][x] == 'b'
        return Triple(m, w, h)
    }

    @Test
    fun `edge background is flooded, enclosed island is not`() {
        val (m, w, h) = mask(
            "bbbbb",
            "b###b",
            "b#b#b",
            "b###b",
            "bbbbb",
        )
        val flooded = GutterFill.floodFromEdges(m, w, h)
        assertTrue(flooded[0])
        assertFalse(flooded[2 * w + 2])
        assertFalse(flooded[1 * w + 1])
    }

    @Test
    fun `full-bleed at the edge is not flooded`() {
        val (m, w, h) = mask(
            "#bbb",
            "#bbb",
        )
        val flooded = GutterFill.floodFromEdges(m, w, h)
        assertFalse(flooded[0])
        assertTrue(flooded[1])
    }
}
