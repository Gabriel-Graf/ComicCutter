package com.panela.comiccutter

import kotlin.test.Test
import kotlin.test.assertEquals

class ReadingOrderTest {
    private val tl = PanelRect(0, 0, 40, 40)
    private val tr = PanelRect(60, 0, 40, 40)
    private val bl = PanelRect(0, 100, 40, 40)
    private val br = PanelRect(60, 100, 40, 40)

    @Test
    fun `LTR reads row by row left to right, top to bottom`() {
        val out = ReadingOrder.sort(listOf(br, tr, bl, tl), ReadingDirection.LEFT_TO_RIGHT)
        assertEquals(listOf(tl, tr, bl, br), out)
    }

    @Test
    fun `RTL reads each row right to left`() {
        val out = ReadingOrder.sort(listOf(tl, tr, bl, br), ReadingDirection.RIGHT_TO_LEFT)
        assertEquals(listOf(tr, tl, br, bl), out)
    }

    @Test
    fun `empty list stays empty`() {
        assertEquals(emptyList(), ReadingOrder.sort(emptyList(), ReadingDirection.LEFT_TO_RIGHT))
    }
}
