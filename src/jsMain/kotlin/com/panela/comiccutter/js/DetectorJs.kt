package com.panela.comiccutter.js

import com.panela.comiccutter.PanelDetector
import com.panela.comiccutter.ReadingDirection
import com.panela.comiccutter.VERSION
import com.panela.comiccutter.model.RenderedPage

/** Die gebaute Lib-Version (= Release-Tag) — für die Demo-Anzeige. */
@JsExport
fun libVersion(): String = VERSION

/** Ein erkanntes Panel in normalisierten Seiten-Koordinaten (0..1), 1-basierte Lesereihenfolge. */
@JsExport
class PanelJs(
    val left: Double,
    val top: Double,
    val width: Double,
    val height: Double,
    val order: Int,
)

/**
 * Browser-API: erkennt Panels einer Seite.
 * @param argb ARGB-Pixel (0xAARRGGBB), Länge width*height, zeilenweise. In JS ein Int32Array.
 * @param rtl true = Manga (rechts→links), false = Comic (links→rechts).
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
