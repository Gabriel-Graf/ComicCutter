package com.panela.comiccutter

import com.panela.comiccutter.model.RenderedPage

/** Binarization of a page: luminance + adaptive Otsu threshold → background mask. Pure Kotlin. */
object ImageBinarization {

    /** Perceived brightness 0..255 from an ARGB pixel (Rec. 601). */
    fun luminance(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    /** Otsu threshold: maximizes inter-class variance over the luminance histogram. */
    fun otsuThreshold(page: RenderedPage): Int {
        val hist = IntArray(256)
        for (p in page.pixels) hist[luminance(p)]++
        val total = page.pixels.size
        if (total == 0) return 127
        var sumAll = 0.0
        for (t in 0..255) sumAll += t.toDouble() * hist[t]
        var sumB = 0.0
        var wB = 0
        var maxVar = -1.0
        var threshold = 127
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += t.toDouble() * hist[t]
            val mB = sumB / wB
            val mF = (sumAll - sumB) / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > maxVar) { maxVar = between; threshold = t }
        }
        return threshold
    }

    /** true = background (luminance > [threshold]). */
    fun backgroundMask(page: RenderedPage, threshold: Int): BooleanArray =
        BooleanArray(page.pixels.size) { luminance(page.pixels[it]) > threshold }
}
