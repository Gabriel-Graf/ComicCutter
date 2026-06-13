package com.panela.comiccutter

import com.panela.comiccutter.model.RenderedPage

/**
 * Detects panels via a hybrid flood-fill + connected-components approach (pure Kotlin, host-testable):
 * Otsu binarization → edge-seed gutter flood → component bounding boxes → filter/merge →
 * reading order. The white gutter mesh is connected from the page edge, so the flood separates panels
 * even when art protrudes into the gutter (unlike the earlier XY-cut).
 *
 * @param minPanelAreaFraction  Boxes smaller than this fraction of the page area are discarded.
 * @param containmentFraction   A box counts as "contained" if this fraction of its area lies within a larger one.
 */
class PanelDetector(
    private val minPanelAreaFraction: Double = 0.01,
    private val containmentFraction: Double = 0.8,
    private val mergeOverSplits: Boolean = true,
    private val gpcFloodArbitration: Boolean = true,
    private val floodArbMaxGpc: Int = 2,
    private val floodArbDelta: Int = 3,
) {
    private companion object {
        /** Upper bound for flood-rescue boxes; above this the flood likely fragments a splash. */
        const val MAX_FLOOD_RESCUE = 5

        /** Pearson correlation on both sides of an edge at or above which two tiles are merged into one panel. */
        const val MERGE_SIM = 0.7

        /** Pages wider than this are downscaled before detection (tuning and performance point). */
        const val DETECTION_WIDTH = 1000
    }

    fun detect(page: RenderedPage, direction: ReadingDirection): List<PanelRect> {
        if (page.width <= 0 || page.height <= 0 || page.pixels.isEmpty()) return emptyList()
        // Downscale to DETECTION_WIDTH (shrink only): thresholds (noise floor, gutter widths) are
        // tuned for ~1000px; larger inputs shift them and waste compute time. Detection runs on the
        // small copy, and the boxes are scaled back up.
        val work = downscaled(page)
        val raw = detectSinglePage(work)
        val merged = if (mergeOverSplits) mergeBoxes(raw, work) else raw
        val result = if (work === page) merged
        else merged.map { scaleRect(it, page.width.toDouble() / work.width) }
        return ReadingOrder.sort(result, direction)
    }

    /** Area-average downscale to [DETECTION_WIDTH] width (aspect-preserving); smaller pages unchanged. */
    private fun downscaled(page: RenderedPage): RenderedPage {
        val sw = page.width
        if (sw <= DETECTION_WIDTH) return page
        val sh = page.height
        val tw = DETECTION_WIDTH
        // Int arithmetic (no Long): Kotlin/JS emulates Long → extremely slow in a per-pixel loop.
        // tx·sw / ty·sh stay < 2^31 for any real page (width/height ≪ 2.1e9).
        val th = (sh * tw / sw).coerceAtLeast(1)
        val src = page.pixels
        val out = IntArray(tw * th)
        for (ty in 0 until th) {
            val sy0 = ty * sh / th
            val sy1 = ((ty + 1) * sh / th).coerceAtLeast(sy0 + 1)
            for (tx in 0 until tw) {
                val sx0 = tx * sw / tw
                val sx1 = ((tx + 1) * sw / tw).coerceAtLeast(sx0 + 1)
                var r = 0; var g = 0; var b = 0; var n = 0
                var sy = sy0
                while (sy < sy1) {
                    val row = sy * sw
                    var sx = sx0
                    while (sx < sx1) {
                        val p = src[row + sx]
                        r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF; n++
                        sx++
                    }
                    sy++
                }
                out[ty * tw + tx] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
        return RenderedPage(tw, th, out)
    }

    private fun scaleRect(r: PanelRect, factor: Double): PanelRect = PanelRect(
        (r.x * factor).toInt(), (r.y * factor).toInt(),
        (r.width * factor).toInt(), (r.height * factor).toInt(),
    )

    /**
     * Detects the panels of a single page (unsorted). Primarily the combined profile XY-cut
     * ([GutterProfileCut]) — measurably more robust against hand-annotated GT than the white-gutter
     * flood, because it separates white (including bubble-bridged) AND dark gutters color-agnostically.
     * If it yields <2 panels (a true full-bleed splash page or indivisible), the flood serves as a
     * safety net.
     */
    private fun detectSinglePage(page: RenderedPage): List<PanelRect> {
        if (page.width <= 0 || page.height <= 0 || page.pixels.isEmpty()) return emptyList()
        val profile = gutterProfileDetect(page)
        if (profile.size >= 2) {
            // Bordered dense grid: GPC's full-width projection under-segments, because crossing panel
            // borders make every gutter row bimodal. The white-gutter flood, by contrast, follows the
            // connected mesh and finds the grid. Only when GPC yielded FEW panels and the flood
            // significantly MORE (threshold) is the flood preferred — otherwise GPC stays (robust by default).
            if (gpcFloodArbitration && profile.size <= floodArbMaxGpc) {
                val flood = floodDetect(page)
                if (flood.size >= profile.size + floodArbDelta) return flood
            }
            return profile
        }
        // The profile found no gutter (full-bleed splash OR white gutters that escape the projection
        // because bubbles/text disturb the row/column statistics). The white-gutter flood is
        // complementary here: on a true splash it also finds nothing (1 box), but on a missed
        // multi-panel layout it finds the gutter mesh reachable from the edge.
        val flood = floodDetect(page)
        if (flood.size in 2..MAX_FLOOD_RESCUE) return flood
        // Otherwise keep the profile result (1 panel = splash). Only when the profile found NOTHING at
        // all (blank/synthetic area) does the flood supply the single full-page panel.
        return profile.ifEmpty { flood }
    }

    /**
     * Merge pass: merges adjacent tiles whose shared edge shows content continuity (the same scene
     * continues = over-split at internal structure such as a steel beam / building edge, NOT a real
     * gutter). Across a real gutter the two scenes differ → low correlation, stays separated. Measured
     * against hand-annotated GT: precision +~0.04 at a small recall cost.
     */
    private fun mergeBoxes(boxes: List<PanelRect>, page: RenderedPage): List<PanelRect> {
        if (boxes.size < 2) return boxes
        val lum = IntArray(page.pixels.size) { ImageBinarization.luminance(page.pixels[it]) }
        val list = boxes.toMutableList()
        var changed = true
        while (changed && list.size > 1) {
            changed = false
            loop@ for (i in list.indices) {
                for (j in i + 1 until list.size) {
                    if (continuousNeighbours(list[i], list[j], lum, page.width, page.height)) {
                        list[i] = union(list[i], list[j])
                        list.removeAt(j)
                        changed = true
                        break@loop
                    }
                }
            }
        }
        return list
    }

    /** Adjacent (shared edge + ≥60% overlap) AND content-continuous across the edge. */
    private fun continuousNeighbours(a: PanelRect, b: PanelRect, lum: IntArray, w: Int, h: Int): Boolean {
        val gap = 10
        val yOv = overlapLen(a.y, a.y + a.height, b.y, b.y + b.height)
        if (yOv >= 0.6 * minOf(a.height, b.height)) {
            val touch = minOf(kotlin.math.abs(a.x + a.width - b.x), kotlin.math.abs(b.x + b.width - a.x))
            if (touch <= gap) {
                val cx = (minOf(a.x + a.width, b.x + b.width) + maxOf(a.x, b.x)) / 2
                val y0 = maxOf(a.y, b.y); val y1 = minOf(a.y + a.height, b.y + b.height)
                return continuity(lum, w, h, cx, y0, y1, vertical = true) >= MERGE_SIM
            }
        }
        val xOv = overlapLen(a.x, a.x + a.width, b.x, b.x + b.width)
        if (xOv >= 0.6 * minOf(a.width, b.width)) {
            val touch = minOf(kotlin.math.abs(a.y + a.height - b.y), kotlin.math.abs(b.y + b.height - a.y))
            if (touch <= gap) {
                val cy = (minOf(a.y + a.height, b.y + b.height) + maxOf(a.y, b.y)) / 2
                val x0 = maxOf(a.x, b.x); val x1 = minOf(a.x + a.width, b.x + b.width)
                return continuity(lum, w, h, cy, x0, x1, vertical = false) >= MERGE_SIM
            }
        }
        return false
    }

    /** Pearson correlation of the mean luminance profiles of the [band] pixels on both sides of the edge. */
    private fun continuity(lum: IntArray, w: Int, h: Int, c: Int, lo: Int, hi: Int, vertical: Boolean, band: Int = 4): Double {
        val n = hi - lo
        if (n < 20) return 0.0
        if (vertical) { if (c - band < 0 || c + band >= w) return 0.0 } else { if (c - band < 0 || c + band >= h) return 0.0 }
        val left = DoubleArray(n); val right = DoubleArray(n)
        for (k in 0 until n) {
            var ls = 0.0; var rs = 0.0
            for (d in 0 until band) {
                if (vertical) {
                    val y = lo + k
                    ls += lum[y * w + (c - band + d)]; rs += lum[y * w + (c + d)]
                } else {
                    val x = lo + k
                    ls += lum[(c - band + d) * w + x]; rs += lum[(c + d) * w + x]
                }
            }
            left[k] = ls / band; right[k] = rs / band
        }
        return pearson(left, right)
    }

    private fun pearson(x: DoubleArray, y: DoubleArray): Double {
        val n = x.size
        var sx = 0.0; var sy = 0.0
        for (i in 0 until n) { sx += x[i]; sy += y[i] }
        val mx = sx / n; val my = sy / n
        var cov = 0.0; var vx = 0.0; var vy = 0.0
        for (i in 0 until n) { val dx = x[i] - mx; val dy = y[i] - my; cov += dx * dy; vx += dx * dx; vy += dy * dy }
        if (vx < 1e-9 || vy < 1e-9) return 0.0
        return cov / kotlin.math.sqrt(vx * vy)
    }

    private fun overlapLen(a0: Int, a1: Int, b0: Int, b1: Int): Int = maxOf(0, minOf(a1, b1) - maxOf(a0, b0))

    private fun union(a: PanelRect, b: PanelRect): PanelRect {
        val x = minOf(a.x, b.x); val y = minOf(a.y, b.y)
        val x2 = maxOf(a.x + a.width, b.x + b.width); val y2 = maxOf(a.y + a.height, b.y + b.height)
        return PanelRect(x, y, x2 - x, y2 - y)
    }

    /**
     * Combined profile XY-cut. The guillotine tiles are disjoint by construction, hence NO containment
     * post-filter (it would only cost valid panels here — measured ~0.09 recall).
     */
    private fun gutterProfileDetect(page: RenderedPage): List<PanelRect> =
        GutterProfileCut.detect(page)

    /** White-gutter flood path (edge-seed flood → components → border split) including post-filter. */
    private fun floodDetect(page: RenderedPage): List<PanelRect> {
        val threshold = ImageBinarization.otsuThreshold(page)
        val background = ImageBinarization.backgroundMask(page, threshold)
        val flooded = GutterFill.floodFromEdges(background, page.width, page.height)
        val regions = RegionLabeling.labelRegions(flooded, page.width, page.height)

        val minArea = page.width.toLong() * page.height * minPanelAreaFraction
        val filtered = regions.filter { it.width.toLong() * it.height >= minArea }

        val darkMask = BooleanArray(background.size) { !background[it] }
        // Split page-spanning components (black frame grids without a white gutter)
        val expanded = filtered.flatMap { box ->
            val wide = box.width > page.width * 0.6 && box.height > page.height * 0.5
            val bandlike = box.width > page.width * 0.85 || box.height > page.height * 0.85
            if (wide || bandlike) BorderLineSplit.split(darkMask, page.width, page.height, box) else listOf(box)
        }
        val sized = expanded.filter { it.width.toLong() * it.height >= minArea }
        return postFilter(sized, darkMask, page)
    }

    /** Shared post-filter for both paths: solid blobs, contained bubbles, overlaps. */
    private fun postFilter(boxes: List<PanelRect>, dark: BooleanArray, page: RenderedPage): List<PanelRect> {
        val deSolid = dropSolidBlobs(boxes, dark, page.width, page.height)
        val deBubbled = dropContainedSmall(deSolid, page.width, page.height)
        return dropContained(deBubbled)
    }

    /**
     * Discards small, almost entirely dark boxes (sound-effect letters like "BLAM", solid
     * silhouettes) — those are not panels. Real (even small) panels have bright areas/speech bubbles,
     * so their dark fraction stays below [minDarkFill]. Area-gated ([maxAreaFraction]) so that only
     * small blobs are affected, never whole panels.
     */
    private fun dropSolidBlobs(
        boxes: List<PanelRect>, dark: BooleanArray, pageW: Int, pageH: Int,
        maxAreaFraction: Double = 0.10, minDarkFill: Double = 0.72,
    ): List<PanelRect> {
        val pageArea = pageW.toLong() * pageH
        return boxes.filterNot { b ->
            val area = b.width.toLong() * b.height
            if (area >= pageArea * maxAreaFraction) return@filterNot false
            var d = 0L
            val x1 = (b.x + b.width).coerceAtMost(pageW); val y1 = (b.y + b.height).coerceAtMost(pageH)
            for (y in b.y until y1) for (x in b.x until x1) if (dark[y * pageW + x]) d++
            val fill = d.toDouble() / area.coerceAtLeast(1)
            fill >= minDarkFill
        }
    }

    /**
     * Discards small boxes (< [smallAreaFraction] of the page area) that lie entirely within a larger
     * one (speech bubbles).
     */
    private fun dropContainedSmall(
        boxes: List<PanelRect>, pageW: Int, pageH: Int, smallAreaFraction: Double = 0.06,
    ): List<PanelRect> {
        val pageArea = pageW.toLong() * pageH
        return boxes.filterNot { b ->
            val small = b.width.toLong() * b.height < pageArea * smallAreaFraction
            small && boxes.any { o ->
                o !== b && o.width.toLong() * o.height > b.width.toLong() * b.height &&
                    b.x >= o.x && b.y >= o.y && b.x + b.width <= o.x + o.width && b.y + b.height <= o.y + o.height
            }
        }
    }

    /** Removes boxes that lie within another (larger) one by [containmentFraction]. */
    private fun dropContained(boxes: List<PanelRect>): List<PanelRect> {
        val bySize = boxes.sortedByDescending { it.width.toLong() * it.height }
        val kept = mutableListOf<PanelRect>()
        for (b in bySize) {
            val area = b.width.toLong() * b.height
            val contained = kept.any { k ->
                val ix = maxOf(b.x, k.x); val iy = maxOf(b.y, k.y)
                val ax = minOf(b.x + b.width, k.x + k.width); val ay = minOf(b.y + b.height, k.y + k.height)
                val iw = ax - ix; val ih = ay - iy
                if (iw <= 0 || ih <= 0) false
                else iw.toLong() * ih >= area * containmentFraction
            }
            if (!contained) kept.add(b)
        }
        return kept
    }
}
