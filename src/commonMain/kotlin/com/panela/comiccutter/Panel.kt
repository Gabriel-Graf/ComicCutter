package com.panela.comiccutter

/** Rechteckige Abgrenzung eines erkannten Panels innerhalb einer Seite. */
data class PanelRect(val x: Int, val y: Int, val width: Int, val height: Int) {
    val centerX: Int get() = x + width / 2
    val centerY: Int get() = y + height / 2
}

/** Leserichtung: Comics links→rechts, Manga rechts→links. */
enum class ReadingDirection { LEFT_TO_RIGHT, RIGHT_TO_LEFT }
