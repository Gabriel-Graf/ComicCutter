package com.panela.comiccutter

import com.panela.comiccutter.model.RenderedPage

/**
 * Fallback segmentation for full-bleed and dark-on-dark pages where the white-gutter flood
 * ([GutterFill]) fails (panels touch the page edge, the gutter mesh is not reachable from the edge,
 * or the gutters are dark instead of white).
 *
 * Principle (Kumiko-style Sobel edge path as a recursive guillotine XY-cut): A gutter line is an
 * axis-aligned strip with almost NO edge activity — regardless of whether it is white or black.
 * Art areas have high edge density. A binary edge image is formed from the Sobel gradient magnitude
 * (adaptive percentile threshold), then the edge density is measured per row/column of a region; a
 * connected INTERIOR valley of low density = gutter. Unlike a pure uniformity threshold, the edge
 * density tolerates individual crossing speech bubbles / art spikes — the gutter stays edge-sparse
 * overall.
 *
 * Practical finding (measured against hand-annotated GT): pure edge density wrongly qualifies white
 * text panels as gutters (white, but edge-rich) and misses gutters bridged by speech bubbles. A
 * combined per-row/column signal is more robust:
 *
 *   Gutter  ⇔  (bright fraction ≥ [minBrightFraction]  AND  edge density ≤ [maxGutterDensity])
 *              OR  luminance std ≤ [maxGutterStd]
 *
 * The AND term catches white gutters while tolerating bridging bubbles/border lines (bright fraction
 * stays high, edge density low), but excludes text panels (bright, but edge-rich). The OR-std term
 * catches perfectly uniform gutters of any color — in particular dark-on-dark.
 *
 * The cut is made at the valley center; each tile is then trimmed to its edge bounding box (outer
 * margin removed), which matches the ground-truth convention (panel = art rectangle).
 *
 * Pure Kotlin, host-testable. Color-agnostic — catches the edge cases that the brightness-based
 * flood inherently cannot see.
 */
object GutterProfileCut {

    /** Minimum tile edge fraction; below this the area is blank (not a panel). */
    private const val MIN_EDGE_PRESENCE = 0.002

    /**
     * @param edgePercentile  Percentile of the gradient magnitude for the edge threshold (0..100).
     * @param minPanel        Minimum tile extent (px) per axis — prevents cuts close to the edge.
     * @param maxGutterDensity Edge density (0..1) of the AND condition for bright gutters.
     * @param minBrightFraction Bright fraction (0..1) of the AND condition for bright gutters.
     * @param brightThreshold Luminance at or above which a pixel counts as "bright" (gutter background).
     * @param maxGutterStd    Luminance std for LARGE boxes (≥ [bigAreaFraction] of the page area), below
     *                        which a row/column (OR term) counts as a uniform gutter — permissive, to
     *                        separate real (including dark) gutters at the page top level.
     * @param maxGutterStdSmall Stricter std for SMALL boxes. Asymmetry: over-splits on internal dark
     *                        structures (steel beams, building edges) arise when recursing INTO a panel
     *                        (small box); a strict std there prevents them without weakening the
     *                        large-scale gutter separation. Measured: raises both precision AND recall.
     * @param bigAreaFraction Area threshold at or above which a box counts as "large" (permissive std).
     * @param noiseK          Page-adaptive std boost: the std threshold is raised by `noiseK ×
     *                        noise floor` of the page. Golden Age newsprint scans have high grain
     *                        (floor ~2-4) — their dark gutters are noisy and exceed the fixed std-20
     *                        threshold, so they would be missed. Clean modern pages have floor ~0 and
     *                        keep the base std → no over-split. Domain detection purely from the image,
     *                        without an external flag.
     * @param gutterDarkLo    Mid-tone exclusion (lower bound): the std term only fires when the row/
     *                        column has a mean luminance ≤ this value (dark ink gutter) …
     * @param gutterBrightHi  … OR ≥ this luminance (bright paper gutter). Uniform MID-tone strips
     *                        (sky, wall in modern art) are thus NOT cut apart as a gutter — this allows
     *                        the higher page-adaptive std without over-splitting modern areas.
     * @param trimContent     Minimum content fraction when trimming; rows/columns below this count as margin.
     * @param minAreaFraction Tiles smaller than this fraction of the page area are dropped.
     */
    fun detect(
        page: RenderedPage,
        edgePercentile: Double = 72.0,
        minPanel: Int = 45,
        maxGutterDensity: Double = 0.22,
        minBrightFraction: Double = 0.90,
        brightThreshold: Int = 200,
        maxGutterStd: Double = 20.0,
        maxGutterStdSmall: Double = 8.0,
        bigAreaFraction: Double = 0.25,
        noiseK: Double = 4.0,
        gutterDarkLo: Int = 95,
        gutterBrightHi: Int = 160,
        trimContent: Double = 0.02,
        minAreaFraction: Double = 0.012,
    ): List<PanelRect> {
        val w = page.width
        val h = page.height
        if (w <= 0 || h <= 0) return emptyList()

        val lum = IntArray(page.pixels.size) { ImageBinarization.luminance(page.pixels[it]) }
        val edge = sobelEdgeMask(lum, w, h, edgePercentile)
        val bright = BooleanArray(lum.size) { lum[it] >= brightThreshold }
        // Content mask: neither bright gutter nor dark gutter → real art area. Otsu-assisted, so that
        // the trim cuts to the art bounding box (matches the GT convention).
        val otsu = ImageBinarization.otsuThreshold(page)
        val brightCut = maxOf(otsu, brightThreshold)
        val darkCut = minOf(otsu, 60)
        val content = BooleanArray(lum.size) { lum[it] < brightCut && lum[it] > darkCut }
        val bigArea = (w.toLong() * h * bigAreaFraction).toLong()
        // Page-adaptive std boost from the grain/noise floor (see noiseK doc).
        val noiseFloor = localNoiseFloor(lum, w, h)
        // Boost ONLY the LARGE-box std (the under-split / top-level separation). The small std stays
        // strict: over-splits on internal dark structures arise when recursing INTO a panel (small
        // box) — raising it there would fragment newsprint panels (measured: precision crash).
        // Asymmetry as with the fixed std (see maxGutterStdSmall doc).
        val noiseBoost = noiseK * noiseFloor
        val params = CutParams(
            minPanel, maxGutterDensity, minBrightFraction,
            maxGutterStd + noiseBoost, maxGutterStdSmall, bigArea,
            gutterDarkLo.toDouble(), gutterBrightHi.toDouble(),
        )
        val tiles = mutableListOf<PanelRect>()
        cut(edge, bright, lum, w, PanelRect(0, 0, w, h), params, depth = 0, out = tiles)

        val minArea = w.toLong() * h * minAreaFraction
        return tiles.mapNotNull { trim(content, w, it, contentFraction = trimContent) }
            .filter { it.width.toLong() * it.height >= minArea }
            .filter { tileEdgeFraction(edge, w, it) > MIN_EDGE_PRESENCE }
    }

    /** Fraction of edge pixels in the tile — truly blank areas (uniform page) have ~0 and are not a panel. */
    private fun tileEdgeFraction(edge: BooleanArray, imgW: Int, box: PanelRect): Double {
        val x1 = box.x + box.width
        val y1 = box.y + box.height
        var c = 0L
        for (y in box.y until y1) for (x in box.x until x1) if (edge[y * imgW + x]) c++
        return c.toDouble() / (box.width.toLong() * box.height).coerceAtLeast(1)
    }

    /** Thresholds for the recursive cut (bundled to avoid long parameter lists). */
    private data class CutParams(
        val minPanel: Int, val maxDensity: Double, val minBright: Double,
        val maxStd: Double, val maxStdSmall: Double, val bigArea: Long,
        val darkLo: Double, val brightHi: Double,
    ) {
        /** Box-size-dependent std threshold (permissive for large, strict for small boxes). */
        fun stdFor(box: PanelRect): Double =
            if (box.width.toLong() * box.height >= bigArea) maxStd else maxStdSmall
    }

    // ── Noise floor ───────────────────────────────────────────────────────────────────────────

    /**
     * Grain/noise floor of the page = 5th percentile of the local luminance std in [win]×[win]
     * windows. The flattest patches (uniform paper/gutter/sky) carry only the sensor/print grain —
     * ~2-4 for newsprint scans (Golden Age), ~0 for clean digital pages. Integral images keep this at
     * O(pixels). The border (half a window) is skipped; irrelevant for the percentile.
     */
    private fun localNoiseFloor(lum: IntArray, w: Int, h: Int, win: Int = 5): Double {
        val r = win / 2
        if (w <= win || h <= win) return 0.0
        val sw = w + 1
        // Double (not Long) for the integral images: Kotlin/JS emulates Long as an object — a
        // per-pixel Long loop over ~1.6 MP costs ~1 s in the browser. Double is JS-native and
        // represents the sums exactly (sumSq ≤ 255²·pixels ≈ 1e11 ≪ 2^53). Identical on the JVM.
        val sum = DoubleArray(sw * (h + 1))
        val sumSq = DoubleArray(sw * (h + 1))
        for (y in 0 until h) {
            var rowSum = 0.0
            var rowSqr = 0.0
            val above = y * sw
            val cur = (y + 1) * sw
            for (x in 0 until w) {
                val v = lum[y * w + x].toDouble()
                rowSum += v; rowSqr += v * v
                sum[cur + x + 1] = sum[above + x + 1] + rowSum
                sumSq[cur + x + 1] = sumSq[above + x + 1] + rowSqr
            }
        }
        val area = (win * win).toDouble()
        val hist = IntArray(256)
        var count = 0
        for (y in r until h - r) for (x in r until w - r) {
            val y0 = y - r; val y1 = y + r + 1; val x0 = x - r; val x1 = x + r + 1
            val s = sum[y1 * sw + x1] - sum[y0 * sw + x1] - sum[y1 * sw + x0] + sum[y0 * sw + x0]
            val sq = sumSq[y1 * sw + x1] - sumSq[y0 * sw + x1] - sumSq[y1 * sw + x0] + sumSq[y0 * sw + x0]
            val mean = s / area
            val variance = (sq / area - mean * mean).coerceAtLeast(0.0)
            val std = kotlin.math.sqrt(variance).toInt().coerceIn(0, 255)
            hist[std]++; count++
        }
        if (count == 0) return 0.0
        val target = (count * 0.05).toLong()
        var cum = 0L
        for (v in hist.indices) {
            cum += hist[v]
            if (cum >= target) return v.toDouble()
        }
        return 0.0
    }

    // ── Edge image ────────────────────────────────────────────────────────────────────────────

    /** Sobel magnitude |gx|+|gy| > adaptive percentile threshold → edge bitmask. */
    private fun sobelEdgeMask(lum: IntArray, w: Int, h: Int, percentile: Double): BooleanArray {
        val mag = IntArray(lum.size)
        val hist = IntArray(1021) // |gx|+|gy| ∈ 0..1020
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val gx = if (x in 1 until w - 1) lum[i + 1] - lum[i - 1] else 0
            val gy = if (y in 1 until h - 1) lum[i + w] - lum[i - w] else 0
            val m = (if (gx < 0) -gx else gx) + (if (gy < 0) -gy else gy)
            mag[i] = m
            hist[m]++
        }
        val threshold = percentileFromHistogram(hist, lum.size, percentile).coerceAtLeast(10)
        return BooleanArray(lum.size) { mag[it] > threshold }
    }

    private fun percentileFromHistogram(hist: IntArray, total: Int, percentile: Double): Int {
        val target = (total * percentile / 100.0).toLong()
        var cum = 0L
        for (v in hist.indices) {
            cum += hist[v]
            if (cum >= target) return v
        }
        return hist.size - 1
    }

    // ── Recursive cut ───────────────────────────────────────────────────────────────────────────

    private fun cut(
        edge: BooleanArray, bright: BooleanArray, lum: IntArray, imgW: Int, box: PanelRect,
        p: CutParams, depth: Int, out: MutableList<PanelRect>,
    ) {
        if (depth > 14) { out.add(box); return }

        val maxStd = p.stdFor(box)
        val rowCut = widestInteriorValley(
            rowDensity(edge, imgW, box), rowFraction(bright, imgW, box),
            rowStd(lum, imgW, box), rowMean(lum, imgW, box), p, maxStd,
        )
        val colCut = widestInteriorValley(
            colDensity(edge, imgW, box), colFraction(bright, imgW, box),
            colStd(lum, imgW, box), colMean(lum, imgW, box), p, maxStd,
        )

        // Wider valley = cleaner separation.
        val preferRow = when {
            rowCut == null -> false
            colCut == null -> true
            else -> rowCut.width >= colCut.width
        }

        when {
            preferRow && rowCut != null -> {
                val c = rowCut.center
                cut(edge, bright, lum, imgW, PanelRect(box.x, box.y, box.width, c), p, depth + 1, out)
                cut(edge, bright, lum, imgW, PanelRect(box.x, box.y + c, box.width, box.height - c), p, depth + 1, out)
            }
            colCut != null -> {
                val c = colCut.center
                cut(edge, bright, lum, imgW, PanelRect(box.x, box.y, c, box.height), p, depth + 1, out)
                cut(edge, bright, lum, imgW, PanelRect(box.x + c, box.y, box.width - c, box.height), p, depth + 1, out)
            }
            else -> out.add(box)
        }
    }

    /** Cut candidate: local index (relative to the box axis) of the valley center + valley width. */
    private data class Cut(val center: Int, val width: Int)

    /**
     * Widest INTERIOR valley with its center ≥ [CutParams.minPanel] from both ends. A profile position
     * belongs to the valley if (bright fraction ≥ minBright AND edge density ≤ maxDensity) OR std ≤
     * maxStd (see class doc). Edges (run touches the box border) are margins, not gutters → excluded.
     *
     * Deliberately WITHOUT profile smoothing: measured against hand-annotated GT, a width-3 smoothing
     * erases thin (few-px-wide) gutters and costs measurable recall (0.53 → 0.48). The OR/AND criterion
     * is robust enough against single-pixel noise, because a valley of exactly one position already
     * yields a valid cut — and the narrowest real gutters should be preserved.
     */
    private fun widestInteriorValley(
        density: DoubleArray, bright: DoubleArray, std: DoubleArray, mean: DoubleArray, p: CutParams, maxStd: Double,
    ): Cut? {
        val n = density.size
        if (n < 2 * p.minPanel + 1) return null
        // Std term only for color-EXTREME (dark ink OR bright paper) strips — mid-tone excluded, so
        // that uniform art areas are not cut apart (see detect doc).
        fun isGutter(i: Int) = (bright[i] >= p.minBright && density[i] <= p.maxDensity) ||
            (std[i] <= maxStd && (mean[i] <= p.darkLo || mean[i] >= p.brightHi))
        var best: Cut? = null
        var i = 0
        while (i < n) {
            if (isGutter(i)) {
                var j = i
                while (j < n && isGutter(j)) j++
                val interior = i > 0 && j < n
                val center = (i + j) / 2
                val width = j - i
                if (interior && center in p.minPanel..(n - p.minPanel) && (best == null || width > best.width)) {
                    best = Cut(center, width)
                }
                i = j
            } else {
                i++
            }
        }
        return best
    }

    // ── Trim to the content bounding box ────────────────────────────────────────────────────────

    /** Cuts off outer margins (gutter/margin): keeps the area with content fraction > [contentFraction]. */
    private fun trim(content: BooleanArray, imgW: Int, box: PanelRect, contentFraction: Double): PanelRect? {
        val rows = rowFraction(content, imgW, box)
        val cols = colFraction(content, imgW, box)
        val top = rows.indexOfFirst { it > contentFraction }
        val bottom = rows.indexOfLast { it > contentFraction }
        val left = cols.indexOfFirst { it > contentFraction }
        val right = cols.indexOfLast { it > contentFraction }
        if (top < 0 || left < 0) return null
        return PanelRect(box.x + left, box.y + top, right - left + 1, bottom - top + 1)
    }

    // ── Edge-density profiles ───────────────────────────────────────────────────────────────────

    /** Per row of the box: fraction of edge pixels across the columns (horizontal gutter detection). */
    private fun rowDensity(edge: BooleanArray, imgW: Int, box: PanelRect): DoubleArray {
        val x1 = box.x + box.width
        val cols = box.width.coerceAtLeast(1)
        return DoubleArray(box.height) { r ->
            val y = box.y + r
            var c = 0
            var x = box.x
            while (x < x1) { if (edge[y * imgW + x]) c++; x++ }
            c.toDouble() / cols
        }
    }

    /** Per column of the box: fraction of edge pixels across the rows (vertical gutter detection). */
    private fun colDensity(edge: BooleanArray, imgW: Int, box: PanelRect): DoubleArray {
        val y1 = box.y + box.height
        val rows = box.height.coerceAtLeast(1)
        return DoubleArray(box.width) { c ->
            val x = box.x + c
            var cnt = 0
            var y = box.y
            while (y < y1) { if (edge[y * imgW + x]) cnt++; y++ }
            cnt.toDouble() / rows
        }
    }

    /** Per row of the box: fraction of bright pixels across the columns (bright gutter, tolerates bubbles). */
    private fun rowFraction(bright: BooleanArray, imgW: Int, box: PanelRect): DoubleArray {
        val x1 = box.x + box.width
        val cols = box.width.coerceAtLeast(1)
        return DoubleArray(box.height) { r ->
            val y = box.y + r
            var c = 0
            var x = box.x
            while (x < x1) { if (bright[y * imgW + x]) c++; x++ }
            c.toDouble() / cols
        }
    }

    /** Per column of the box: fraction of bright pixels across the rows. */
    private fun colFraction(bright: BooleanArray, imgW: Int, box: PanelRect): DoubleArray {
        val y1 = box.y + box.height
        val rows = box.height.coerceAtLeast(1)
        return DoubleArray(box.width) { c ->
            val x = box.x + c
            var cnt = 0
            var y = box.y
            while (y < y1) { if (bright[y * imgW + x]) cnt++; y++ }
            cnt.toDouble() / rows
        }
    }

    /** Per row of the box: luminance std across the columns (uniform strip = gutter). */
    private fun rowStd(lum: IntArray, imgW: Int, box: PanelRect): DoubleArray {
        val x1 = box.x + box.width
        val cols = box.width.coerceAtLeast(1)
        return DoubleArray(box.height) { r ->
            val y = box.y + r
            var sum = 0.0; var sumSq = 0.0
            var x = box.x
            while (x < x1) { val v = lum[y * imgW + x].toDouble(); sum += v; sumSq += v * v; x++ }
            stdOf(sum, sumSq, cols)
        }
    }

    /** Per column of the box: luminance std across the rows. */
    private fun colStd(lum: IntArray, imgW: Int, box: PanelRect): DoubleArray {
        val y1 = box.y + box.height
        val rows = box.height.coerceAtLeast(1)
        return DoubleArray(box.width) { c ->
            val x = box.x + c
            var sum = 0.0; var sumSq = 0.0
            var y = box.y
            while (y < y1) { val v = lum[y * imgW + x].toDouble(); sum += v; sumSq += v * v; y++ }
            stdOf(sum, sumSq, rows)
        }
    }

    /** Per row of the box: mean luminance across the columns (for the mid-tone exclusion). */
    private fun rowMean(lum: IntArray, imgW: Int, box: PanelRect): DoubleArray {
        val x1 = box.x + box.width
        val cols = box.width.coerceAtLeast(1)
        return DoubleArray(box.height) { r ->
            val y = box.y + r
            var sum = 0L
            var x = box.x
            while (x < x1) { sum += lum[y * imgW + x]; x++ }
            sum.toDouble() / cols
        }
    }

    /** Per column of the box: mean luminance across the rows. */
    private fun colMean(lum: IntArray, imgW: Int, box: PanelRect): DoubleArray {
        val y1 = box.y + box.height
        val rows = box.height.coerceAtLeast(1)
        return DoubleArray(box.width) { c ->
            val x = box.x + c
            var sum = 0L
            var y = box.y
            while (y < y1) { sum += lum[y * imgW + x]; y++ }
            sum.toDouble() / rows
        }
    }

    private fun stdOf(sum: Double, sumSq: Double, n: Int): Double {
        val mean = sum / n
        return kotlin.math.sqrt((sumSq / n - mean * mean).coerceAtLeast(0.0))
    }
}
