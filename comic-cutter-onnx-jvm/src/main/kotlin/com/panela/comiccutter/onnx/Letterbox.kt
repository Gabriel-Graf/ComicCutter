package com.panela.comiccutter.onnx

import kotlin.math.roundToInt

/**
 * Aspect-erhaltendes Letterbox-Mapping Seite ↔ quadratischer Modell-Input [target]×[target].
 * Spiegelt die Referenz-Preprocessing-Geometrie des Trainings ([adapter.py]/`spike_lib.py`):
 * `r = min(target/h, target/w)`, skalierte Größe gerundet, Rest als zentriertes Padding (Farbe 114).
 */
class Letterbox private constructor(
    val scale: Double,
    val padX: Int,
    val padY: Int,
) {
    /** Quell-Pixel-Spaltenindex zu einer Modellraum-x-Koordinate (für das Forward-Rendering). */
    fun sourceX(targetX: Int): Int = ((targetX - padX) / scale).toInt()

    /** Quell-Pixel-Zeilenindex zu einer Modellraum-y-Koordinate. */
    fun sourceY(targetY: Int): Int = ((targetY - padY) / scale).toInt()

    /** Modellraum-Box (Mitte cx,cy + w,h) → Seiten-Pixel-Box (x,y top-left + w,h), zurückprojiziert. */
    fun boxFromCenter(cx: Float, cy: Float, w: Float, h: Float): IntArray {
        val x1 = (cx - w / 2 - padX) / scale
        val y1 = (cy - h / 2 - padY) / scale
        val x2 = (cx + w / 2 - padX) / scale
        val y2 = (cy + h / 2 - padY) / scale
        return intArrayOf(x1.roundToInt(), y1.roundToInt(), (x2 - x1).roundToInt(), (y2 - y1).roundToInt())
    }

    companion object {
        fun of(srcW: Int, srcH: Int, target: Int): Letterbox {
            val scale = minOf(target.toDouble() / srcH, target.toDouble() / srcW)
            val newW = (srcW * scale).roundToInt()
            val newH = (srcH * scale).roundToInt()
            return Letterbox(scale, (target - newW) / 2, (target - newH) / 2)
        }
    }
}
