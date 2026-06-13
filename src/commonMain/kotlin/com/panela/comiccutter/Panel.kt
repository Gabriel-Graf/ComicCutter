package com.panela.comiccutter

/** Rectangular bounds of a detected panel within a page. */
data class PanelRect(val x: Int, val y: Int, val width: Int, val height: Int) {
    val centerX: Int get() = x + width / 2
    val centerY: Int get() = y + height / 2
}

/** Reading direction: comics left-to-right, manga right-to-left. */
enum class ReadingDirection { LEFT_TO_RIGHT, RIGHT_TO_LEFT }
