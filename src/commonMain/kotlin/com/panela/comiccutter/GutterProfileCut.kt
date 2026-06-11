package com.panela.comiccutter

import com.panela.comiccutter.model.RenderedPage

/**
 * Fallback-Segmentierung für full-bleed- und Dunkel-auf-Dunkel-Seiten, bei denen das
 * Weißgutter-Flood ([GutterFill]) versagt (Panels berühren den Seitenrand, das Gutter-Netz
 * ist vom Rand nicht erreichbar, oder die Gassen sind dunkel statt weiß).
 *
 * Prinzip (Kumiko-artiger Sobel-Kantenpfad als rekursiver Guillotine-XY-Cut): Eine Gutter-Linie
 * ist ein achsenparalleler Streifen mit nahezu KEINER Kantenaktivität — egal ob weiß oder schwarz.
 * Zeichenfläche (Art) hat hohe Kantendichte. Über die Sobel-Gradientenmagnitude wird ein
 * Kanten-Binärbild gebildet (adaptive Perzentil-Schwelle), dann pro Zeile/Spalte einer Region die
 * Kantendichte gemessen; ein zusammenhängendes INNERES Tal niedriger Dichte = Gutter. Anders als
 * eine reine Uniformitäts-Schwelle toleriert die Kantendichte einzelne kreuzende Sprechblasen/
 * Art-Spitzen — die Gasse bleibt insgesamt kantenarm.
 *
 * Praxis-Erkenntnis (gemessen gegen Hand-GT): reine Kantendichte qualifiziert weiße Text-Panels
 * fälschlich als Gutter (weiß, aber kantenreich) und verfehlt von Sprechblasen überbrückte Gassen.
 * Robuster ist ein kombiniertes Signal pro Zeile/Spalte:
 *
 *   Gutter  ⇔  (Hell-Anteil ≥ [minBrightFraction]  UND  Kantendichte ≤ [maxGutterDensity])
 *              ODER  Luminanz-Std ≤ [maxGutterStd]
 *
 * Der UND-Term fängt weiße Gassen, toleriert dabei überbrückende Blasen/Rahmenlinien (Hell-Anteil
 * bleibt hoch, Kantendichte niedrig), schließt aber Text-Panels aus (hell, aber kantenreich). Der
 * ODER-Std-Term fängt perfekt uniforme Gassen jeder Farbe — insbesondere Dunkel-auf-Dunkel.
 *
 * Geschnitten wird in der Talmitte; danach wird jede Kachel auf ihre Kanten-Bounding-Box getrimmt
 * (Außenrand/Margin entfernt), was sich mit der Ground-Truth-Konvention (Panel = Art-Rechteck) deckt.
 *
 * Reines Kotlin, host-testbar. Color-agnostisch — fängt die Edge-Cases, die das helligkeits-basierte
 * Flood prinzipiell nicht sehen kann.
 */
object GutterProfileCut {

    /** Kachel-Mindest-Kantenanteil; darunter ist die Fläche blank (kein Panel). */
    private const val MIN_EDGE_PRESENCE = 0.002

    /**
     * @param edgePercentile  Perzentil der Gradientenmagnitude für die Kantenschwelle (0..100).
     * @param minPanel        Minimale Kachelausdehnung (px) je Achse — verhindert Schnitte nah am Rand.
     * @param maxGutterDensity Kantendichte (0..1) der UND-Bedingung für helle Gassen.
     * @param minBrightFraction Hell-Anteil (0..1) der UND-Bedingung für helle Gassen.
     * @param brightThreshold Luminanz, ab der ein Pixel als „hell" (Gutter-Hintergrund) zählt.
     * @param maxGutterStd    Luminanz-Std für GROSSE Boxen (≥ [bigAreaFraction] Seitenfläche), unter der
     *                        eine Zeile/Spalte (ODER-Term) als uniforme Gasse gilt — permissiv, um echte
     *                        (auch dunkle) Gassen am Seiten-Top-Level zu trennen.
     * @param maxGutterStdSmall Strengerer Std für KLEINE Boxen. Asymmetrie: Over-Splits an internen dunklen
     *                        Strukturen (Stahlträger, Gebäudekanten) entstehen beim Rekursieren IN ein Panel
     *                        (kleine Box); ein strikter Std dort verhindert sie, ohne die großflächige
     *                        Gassen-Trennung zu schwächen. Gemessen: hebt Precision UND Recall zugleich.
     * @param bigAreaFraction Flächen-Schwelle, ab der eine Box als „groß" (permissiver Std) gilt.
     * @param trimContent     Content-Mindestanteil beim Trimmen; Zeilen/Spalten darunter gelten als Rand.
     * @param minAreaFraction Kacheln kleiner als dieser Seitenflächenanteil entfallen.
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
        trimContent: Double = 0.02,
        minAreaFraction: Double = 0.012,
    ): List<PanelRect> {
        val w = page.width
        val h = page.height
        if (w <= 0 || h <= 0) return emptyList()

        val lum = IntArray(page.pixels.size) { ImageBinarization.luminance(page.pixels[it]) }
        val edge = sobelEdgeMask(lum, w, h, edgePercentile)
        val bright = BooleanArray(lum.size) { lum[it] >= brightThreshold }
        // Content-Maske: weder hell-Gutter noch dunkel-Gutter → echte Zeichenfläche. Otsu-gestützt,
        // damit der Trim auf die Art-Bounding-Box schneidet (deckt sich mit der GT-Konvention).
        val otsu = ImageBinarization.otsuThreshold(page)
        val brightCut = maxOf(otsu, brightThreshold)
        val darkCut = minOf(otsu, 60)
        val content = BooleanArray(lum.size) { lum[it] < brightCut && lum[it] > darkCut }
        val bigArea = (w.toLong() * h * bigAreaFraction).toLong()
        val params = CutParams(minPanel, maxGutterDensity, minBrightFraction, maxGutterStd, maxGutterStdSmall, bigArea)
        val tiles = mutableListOf<PanelRect>()
        cut(edge, bright, lum, w, PanelRect(0, 0, w, h), params, depth = 0, out = tiles)

        val minArea = w.toLong() * h * minAreaFraction
        return tiles.mapNotNull { trim(content, w, it, contentFraction = trimContent) }
            .filter { it.width.toLong() * it.height >= minArea }
            .filter { tileEdgeFraction(edge, w, it) > MIN_EDGE_PRESENCE }
    }

    /** Anteil Kantenpixel in der Kachel — truly blanke Flächen (uniforme Seite) haben ~0 und sind kein Panel. */
    private fun tileEdgeFraction(edge: BooleanArray, imgW: Int, box: PanelRect): Double {
        val x1 = box.x + box.width
        val y1 = box.y + box.height
        var c = 0L
        for (y in box.y until y1) for (x in box.x until x1) if (edge[y * imgW + x]) c++
        return c.toDouble() / (box.width.toLong() * box.height).coerceAtLeast(1)
    }

    /** Schwellen für den rekursiven Schnitt (gebündelt, um lange Parameterlisten zu vermeiden). */
    private data class CutParams(
        val minPanel: Int, val maxDensity: Double, val minBright: Double,
        val maxStd: Double, val maxStdSmall: Double, val bigArea: Long,
    ) {
        /** Box-größenabhängiger Std-Schwellwert (permissiv für große, strikt für kleine Boxen). */
        fun stdFor(box: PanelRect): Double =
            if (box.width.toLong() * box.height >= bigArea) maxStd else maxStdSmall
    }

    // ── Kantenbild ────────────────────────────────────────────────────────────────────────────

    /** Sobel-Magnitude |gx|+|gy| > adaptive Perzentil-Schwelle → Kanten-Bitmaske. */
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

    // ── Rekursiver Schnitt ──────────────────────────────────────────────────────────────────────

    private fun cut(
        edge: BooleanArray, bright: BooleanArray, lum: IntArray, imgW: Int, box: PanelRect,
        p: CutParams, depth: Int, out: MutableList<PanelRect>,
    ) {
        if (depth > 14) { out.add(box); return }

        val maxStd = p.stdFor(box)
        val rowCut = widestInteriorValley(
            rowDensity(edge, imgW, box), rowFraction(bright, imgW, box), rowStd(lum, imgW, box), p, maxStd,
        )
        val colCut = widestInteriorValley(
            colDensity(edge, imgW, box), colFraction(bright, imgW, box), colStd(lum, imgW, box), p, maxStd,
        )

        // Breiteres Tal = sauberere Trennung.
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

    /** Schnittkandidat: lokaler Index (relativ zur Box-Achse) der Talmitte + Talbreite. */
    private data class Cut(val center: Int, val width: Int)

    /**
     * Breitestes INNERES Tal mit Mitte ≥ [CutParams.minPanel] von beiden Enden. Eine Profilposition
     * zählt zum Tal, wenn (Hell-Anteil ≥ minBright UND Kantendichte ≤ maxDensity) ODER Std ≤ maxStd
     * (siehe Klassen-Doku). Ränder (Lauf berührt Box-Kante) sind Margins, keine Gassen → ausgeschlossen.
     *
     * Bewusst OHNE Profil-Glättung: gegen Hand-GT gemessen löscht eine 3er-Glättung dünne (wenige px
     * breite) Gassen und kostet messbar Recall (0.53 → 0.48). Das ODER-/UND-Kriterium ist robust genug
     * gegen Einzel-Pixel-Rauschen, weil ein Tal von genau einer Position bereits einen gültigen Schnitt
     * ergibt — und engste echte Gassen sollen erhalten bleiben.
     */
    private fun widestInteriorValley(
        density: DoubleArray, bright: DoubleArray, std: DoubleArray, p: CutParams, maxStd: Double,
    ): Cut? {
        val n = density.size
        if (n < 2 * p.minPanel + 1) return null
        fun isGutter(i: Int) = (bright[i] >= p.minBright && density[i] <= p.maxDensity) || std[i] <= maxStd
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

    // ── Trimmen auf die Content-Bounding-Box ────────────────────────────────────────────────────

    /** Schneidet Außenränder (Gutter/Margin) weg: behält den Bereich mit Content-Anteil > [contentFraction]. */
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

    // ── Kantendichte-Profile ────────────────────────────────────────────────────────────────────

    /** Pro Zeile der Box: Anteil Kantenpixel über die Spalten (horizontale Gutter-Erkennung). */
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

    /** Pro Spalte der Box: Anteil Kantenpixel über die Zeilen (vertikale Gutter-Erkennung). */
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

    /** Pro Zeile der Box: Anteil heller Pixel über die Spalten (helle Gasse, toleriert Blasen). */
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

    /** Pro Spalte der Box: Anteil heller Pixel über die Zeilen. */
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

    /** Pro Zeile der Box: Luminanz-Std über die Spalten (uniformer Streifen = Gutter). */
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

    /** Pro Spalte der Box: Luminanz-Std über die Zeilen. */
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

    private fun stdOf(sum: Double, sumSq: Double, n: Int): Double {
        val mean = sum / n
        return kotlin.math.sqrt((sumSq / n - mean * mean).coerceAtLeast(0.0))
    }
}
