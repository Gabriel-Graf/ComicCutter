package com.panela.comiccutter.js

import com.panela.comiccutter.PanelDetector
import com.panela.comiccutter.ReadingDirection
import com.panela.comiccutter.VERSION
import com.panela.comiccutter.model.RenderedPage

/** The built library version (= release tag) — for the demo display. */
@JsExport
fun libVersion(): String = VERSION

/** A detected panel in normalized page coordinates (0..1), 1-based reading order. */
@JsExport
class PanelJs(
    val left: Double,
    val top: Double,
    val width: Double,
    val height: Double,
    val order: Int,
)

/**
 * Browser API: detects the panels of a page.
 * @param argb ARGB pixels (0xAARRGGBB), length width*height, row by row. An Int32Array in JS.
 * @param rtl true = manga (right-to-left), false = comic (left-to-right).
 */
@JsExport
fun detectPanels(width: Int, height: Int, argb: IntArray, rtl: Boolean): Array<PanelJs> {
    if (width <= 0 || height <= 0 || argb.isEmpty()) return emptyArray()
    val page = RenderedPage(width, height, argb)
    val dir = if (rtl) ReadingDirection.RIGHT_TO_LEFT else ReadingDirection.LEFT_TO_RIGHT
    val w = width.toDouble()
    val h = height.toDouble()
    return PanelDetector().detect(page, dir)
        .mapIndexed { i, r ->
            PanelJs(
                left = r.x / w,
                top = r.y / h,
                width = r.width / w,
                height = r.height / h,
                order = i + 1,
            )
        }
        .toTypedArray()
}
