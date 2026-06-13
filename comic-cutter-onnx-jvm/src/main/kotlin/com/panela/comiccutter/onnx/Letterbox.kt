package com.panela.comiccutter.onnx

import kotlin.math.roundToInt

/**
 * Aspect-preserving letterbox mapping between page and square model input [target]×[target].
 * Mirrors the reference preprocessing geometry of the training ([adapter.py]/`spike_lib.py`):
 * `r = min(target/h, target/w)`, scaled size rounded, the remainder as centered padding (color 114).
 */
class Letterbox private constructor(
    val scale: Double,
    val padX: Int,
    val padY: Int,
) {
    /** Source-pixel column index for a model-space x coordinate (for forward rendering). */
    fun sourceX(targetX: Int): Int = ((targetX - padX) / scale).toInt()

    /** Source-pixel row index for a model-space y coordinate. */
    fun sourceY(targetY: Int): Int = ((targetY - padY) / scale).toInt()

    /** Model-space box (center cx,cy + w,h) → page-pixel box (x,y top-left + w,h), back-projected. */
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
