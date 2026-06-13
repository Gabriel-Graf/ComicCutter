package com.panela.comiccutter

/**
 * Recursively splits a region along narrow, continuous DARK axis-aligned border lines that are
 * surrounded by brighter bands — i.e. separates black-bordered / touching panels without
 * fragmenting uniformly dark art areas.
 *
 * Key invariant: A band candidate is only valid if the neighboring rows/columns OUTSIDE the band
 * are significantly brighter (darkFraction ≤ neighborMaxFraction). This prevents uniform black
 * areas from being detected as border lines.
 */
object BorderLineSplit {

    /**
     * Recursively splits [box] along axis-aligned border lines.
     *
     * @param dark                 Binary mask: true = dark/ink pixel
     * @param width                Total image width
     * @param height               Total image height
     * @param box                  Region to examine
     * @param lineDarkFraction     Minimum dark fraction of a row/column to count as a line
     * @param neighborMaxFraction  Maximum allowed dark fraction of the neighboring bands (context condition)
     * @param maxLineThickness     Maximum thickness of a line band in pixels
     * @param minPanel             Minimum panel size (along the cut axis) after the split
     * @param maxDepth             Maximum recursion depth
     */
    fun split(
        dark: BooleanArray,
        width: Int,
        height: Int,
        box: PanelRect,
        lineDarkFraction: Double = 0.7,
        neighborMaxFraction: Double = 0.4,
        maxLineThickness: Int = 24,
        minPanel: Int = 30,
        maxDepth: Int = 8,
    ): List<PanelRect> {
        if (maxDepth <= 0) return listOf(box)

        val hBand = findBestHorizontalBand(dark, width, height, box, lineDarkFraction, neighborMaxFraction, maxLineThickness, minPanel)
        val vBand = findBestVerticalBand(dark, width, height, box, lineDarkFraction, neighborMaxFraction, maxLineThickness, minPanel)

        // Pick the stronger cut (higher mean darkness)
        val cut: CutBand? = when {
            hBand == null && vBand == null -> null
            hBand == null -> vBand
            vBand == null -> hBand
            hBand.score >= vBand.score -> hBand
            else -> vBand
        }

        if (cut == null) return listOf(box)

        return if (cut.horizontal) {
            // Horizontal cut: top box + bottom box, band excluded
            val topHeight = cut.start - box.y
            val bottomY = cut.endExcl
            val bottomHeight = box.y + box.height - bottomY
            if (topHeight < minPanel || bottomHeight < minPanel) return listOf(box)
            val top = PanelRect(box.x, box.y, box.width, topHeight)
            val bottom = PanelRect(box.x, bottomY, box.width, bottomHeight)
            split(dark, width, height, top, lineDarkFraction, neighborMaxFraction, maxLineThickness, minPanel, maxDepth - 1) +
                split(dark, width, height, bottom, lineDarkFraction, neighborMaxFraction, maxLineThickness, minPanel, maxDepth - 1)
        } else {
            // Vertical cut: left box + right box, band excluded
            val leftWidth = cut.start - box.x
            val rightX = cut.endExcl
            val rightWidth = box.x + box.width - rightX
            if (leftWidth < minPanel || rightWidth < minPanel) return listOf(box)
            val left = PanelRect(box.x, box.y, leftWidth, box.height)
            val right = PanelRect(rightX, box.y, rightWidth, box.height)
            split(dark, width, height, left, lineDarkFraction, neighborMaxFraction, maxLineThickness, minPanel, maxDepth - 1) +
                split(dark, width, height, right, lineDarkFraction, neighborMaxFraction, maxLineThickness, minPanel, maxDepth - 1)
        }
    }

    // ── Internal data class for a found line band ─────────────────────────────────────────────

    private data class CutBand(
        val start: Int,       // first row index (inclusive) of the band
        val endExcl: Int,     // first index after the band (exclusive)
        val score: Double,    // mean darkness of the band (higher = better)
        val horizontal: Boolean,
    )

    // ── Horizontal band search ────────────────────────────────────────────────────────────────

    /**
     * Finds the best horizontal border line band within [box].
     * "Interior" means: the band lies at least [minPanel] pixels from the top and bottom edge.
     */
    private fun findBestHorizontalBand(
        dark: BooleanArray,
        width: Int,
        height: Int,
        box: PanelRect,
        lineDarkFraction: Double,
        neighborMaxFraction: Double,
        maxLineThickness: Int,
        minPanel: Int,
    ): CutBand? {
        val yInnerStart = box.y + minPanel
        val yInnerEnd = box.y + box.height - minPanel  // exclusive: band may not start here

        if (yInnerStart >= yInnerEnd) return null

        // Compute dark fraction for all rows in the box range
        val fractions = DoubleArray(box.height) { i ->
            darkFractionRow(dark, width, height, box, box.y + i)
        }

        var bestBand: CutBand? = null

        var y = yInnerStart
        while (y < yInnerEnd) {
            val localY = y - box.y
            if (fractions[localY] >= lineDarkFraction) {
                // Band start found — how far does it extend?
                var bandEnd = y + 1
                while (bandEnd < yInnerEnd + maxLineThickness && bandEnd < box.y + box.height &&
                    fractions[bandEnd - box.y] >= lineDarkFraction
                ) {
                    bandEnd++
                }
                val thickness = bandEnd - y
                if (thickness <= maxLineThickness) {
                    // Check the neighboring bands (outside the band, inside the box)
                    val neighborAbove = neighborFractionAbove(fractions, box, y, neighborRows = 3)
                    val neighborBelow = neighborFractionBelow(fractions, box, bandEnd, neighborRows = 3)
                    if (neighborAbove <= neighborMaxFraction && neighborBelow <= neighborMaxFraction) {
                        val score = fractions.copyOfRange(localY, localY + thickness).average()
                        if (bestBand == null || score > bestBand.score) {
                            bestBand = CutBand(y, bandEnd, score, horizontal = true)
                        }
                    }
                }
                // Jump ahead to the band end
                y = bandEnd
            } else {
                y++
            }
        }
        return bestBand
    }

    // ── Vertical band search ──────────────────────────────────────────────────────────────────

    /**
     * Finds the best vertical border line band within [box].
     * "Interior" means: the band lies at least [minPanel] pixels from the left and right edge.
     */
    private fun findBestVerticalBand(
        dark: BooleanArray,
        width: Int,
        height: Int,
        box: PanelRect,
        lineDarkFraction: Double,
        neighborMaxFraction: Double,
        maxLineThickness: Int,
        minPanel: Int,
    ): CutBand? {
        val xInnerStart = box.x + minPanel
        val xInnerEnd = box.x + box.width - minPanel

        if (xInnerStart >= xInnerEnd) return null

        val fractions = DoubleArray(box.width) { i ->
            darkFractionCol(dark, width, height, box, box.x + i)
        }

        var bestBand: CutBand? = null

        var x = xInnerStart
        while (x < xInnerEnd) {
            val localX = x - box.x
            if (fractions[localX] >= lineDarkFraction) {
                var bandEnd = x + 1
                while (bandEnd < xInnerEnd + maxLineThickness && bandEnd < box.x + box.width &&
                    fractions[bandEnd - box.x] >= lineDarkFraction
                ) {
                    bandEnd++
                }
                val thickness = bandEnd - x
                if (thickness <= maxLineThickness) {
                    val neighborLeft = neighborFractionLeft(fractions, box, x, neighborCols = 3)
                    val neighborRight = neighborFractionRight(fractions, box, bandEnd, neighborCols = 3)
                    if (neighborLeft <= neighborMaxFraction && neighborRight <= neighborMaxFraction) {
                        val score = fractions.copyOfRange(localX, localX + thickness).average()
                        if (bestBand == null || score > bestBand.score) {
                            bestBand = CutBand(x, bandEnd, score, horizontal = false)
                        }
                    }
                }
                x = bandEnd
            } else {
                x++
            }
        }
        return bestBand
    }

    // ── Neighbor-darkness helpers ─────────────────────────────────────────────────────────────

    /** Mean darkness of the [neighborRows] rows directly ABOVE the band (relative to the box). */
    private fun neighborFractionAbove(fractions: DoubleArray, box: PanelRect, bandStartAbs: Int, neighborRows: Int): Double {
        val localStart = bandStartAbs - box.y
        if (localStart <= 0) return 0.0
        val from = maxOf(0, localStart - neighborRows)
        return fractions.copyOfRange(from, localStart).average()
    }

    /** Mean darkness of the [neighborRows] rows directly BELOW the band. */
    private fun neighborFractionBelow(fractions: DoubleArray, box: PanelRect, bandEndAbs: Int, neighborRows: Int): Double {
        val localEnd = bandEndAbs - box.y
        if (localEnd >= fractions.size) return 0.0
        val to = minOf(fractions.size, localEnd + neighborRows)
        return fractions.copyOfRange(localEnd, to).average()
    }

    /** Mean darkness of the [neighborCols] columns directly LEFT of the band. */
    private fun neighborFractionLeft(fractions: DoubleArray, box: PanelRect, bandStartAbs: Int, neighborCols: Int): Double {
        val localStart = bandStartAbs - box.x
        if (localStart <= 0) return 0.0
        val from = maxOf(0, localStart - neighborCols)
        return fractions.copyOfRange(from, localStart).average()
    }

    /** Mean darkness of the [neighborCols] columns directly RIGHT of the band. */
    private fun neighborFractionRight(fractions: DoubleArray, box: PanelRect, bandEndAbs: Int, neighborCols: Int): Double {
        val localEnd = bandEndAbs - box.x
        if (localEnd >= fractions.size) return 0.0
        val to = minOf(fractions.size, localEnd + neighborCols)
        return fractions.copyOfRange(localEnd, to).average()
    }

    // ── Pixel-density helpers (mandated by the spec) ──────────────────────────────────────────

    private fun darkFractionRow(dark: BooleanArray, width: Int, height: Int, box: PanelRect, y: Int): Double {
        if (y < 0 || y >= height) return 0.0
        var c = 0
        val x0 = box.x; val x1 = (box.x + box.width).coerceAtMost(width)
        for (x in x0 until x1) if (dark[y * width + x]) c++
        return c.toDouble() / (x1 - x0).coerceAtLeast(1)
    }

    private fun darkFractionCol(dark: BooleanArray, width: Int, height: Int, box: PanelRect, x: Int): Double {
        if (x < 0 || x >= width) return 0.0
        var c = 0
        val y0 = box.y; val y1 = (box.y + box.height).coerceAtMost(height)
        for (y in y0 until y1) if (dark[y * width + x]) c++
        return c.toDouble() / (y1 - y0).coerceAtLeast(1)
    }
}
