package com.panela.comiccutter

import com.panela.comiccutter.model.RenderedPage

/**
 * Erkennt Panels via Hybrid Flood-Fill + Connected-Components (reines Kotlin, host-testbar):
 * Otsu-Binarisierung → Edge-Seed-Gutter-Flood → Component-Bounding-Boxes → Filter/Merge →
 * Lesereihenfolge. Das weiße Gutter-Netz ist vom Seitenrand zusammenhängend, daher trennt die
 * Flutung Panels auch bei in die Gasse ragender Art (anders als der frühere XY-Cut).
 *
 * @param minPanelAreaFraction  Boxen kleiner als dieser Seitenflächen-Anteil werden verworfen.
 * @param containmentFraction   Box gilt als „enthalten" wenn dieser Anteil ihrer Fläche in einer größeren liegt.
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
        /** Obergrenze für Flood-Rescue-Boxen; darüber zersplittert das Flood vermutlich einen Splash. */
        const val MAX_FLOOD_RESCUE = 5

        /** Pearson-Korrelation beiderseits einer Kante, ab der zwei Kacheln als ein Panel verschmolzen werden. */
        const val MERGE_SIM = 0.7

        /** Seiten breiter als das werden vor der Erkennung herunterskaliert (Tuning- und Tempo-Punkt). */
        const val DETECTION_WIDTH = 1000
    }

    fun detect(page: RenderedPage, direction: ReadingDirection): List<PanelRect> {
        if (page.width <= 0 || page.height <= 0 || page.pixels.isEmpty()) return emptyList()
        // Auf DETECTION_WIDTH herunterskalieren (nur verkleinern): Schwellwerte (Korn-Floor,
        // Gutter-Breiten) sind auf ~1000px getunt; größere Eingaben verschieben sie und kosten unnötig
        // Rechenzeit. Erkennung läuft auf der kleinen Kopie, die Boxen werden zurückskaliert.
        val work = downscaled(page)
        val raw = detectSinglePage(work)
        val merged = if (mergeOverSplits) mergeBoxes(raw, work) else raw
        val result = if (work === page) merged
        else merged.map { scaleRect(it, page.width.toDouble() / work.width) }
        return ReadingOrder.sort(result, direction)
    }

    /** Flächenmittel-Downscale auf [DETECTION_WIDTH] Breite (aspect-erhaltend); kleinere Seiten unverändert. */
    private fun downscaled(page: RenderedPage): RenderedPage {
        val sw = page.width
        if (sw <= DETECTION_WIDTH) return page
        val sh = page.height
        val tw = DETECTION_WIDTH
        // Int-Arithmetik (kein Long): Kotlin/JS emuliert Long → in einer Pro-Pixel-Schleife extrem
        // langsam. tx·sw / ty·sh bleiben für jede reale Seite < 2^31 (Breite/Höhe ≪ 2.1e9).
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
     * Erkennt Panels einer einzelnen Seite (unsortiert). Primär der kombinierte Profil-XY-Cut
     * ([GutterProfileCut]) — gegen Hand-GT messbar robuster als das Weißgutter-Flood, weil er weiße
     * (auch von Blasen überbrückte) UND dunkle Gassen color-agnostisch trennt. Liefert er <2 Panels
     * (echte full-bleed-Splash-Seite oder unteilbar), greift das Flood als Sicherheitsnetz.
     */
    private fun detectSinglePage(page: RenderedPage): List<PanelRect> {
        if (page.width <= 0 || page.height <= 0 || page.pixels.isEmpty()) return emptyList()
        val profile = gutterProfileDetect(page)
        if (profile.size >= 2) {
            // Bordered dense grid: GPCs Voll-Breiten-Projektion unter-segmentiert, weil querende
            // Panel-Rahmen jede Gutter-Zeile bimodal machen. Das Weißgutter-Flood folgt dagegen dem
            // zusammenhängenden Netz und findet das Raster. Nur wenn GPC WENIG Panels lieferte und
            // Flood DEUTLICH mehr (Schwelle), wird Flood bevorzugt — sonst bleibt GPC (Default-robust).
            if (gpcFloodArbitration && profile.size <= floodArbMaxGpc) {
                val flood = floodDetect(page)
                if (flood.size >= profile.size + floodArbDelta) return flood
            }
            return profile
        }
        // Profil fand keine Gasse (full-bleed-Splash ODER weiße Gassen, die der Projektion entgehen,
        // weil Blasen/Text die Zeilen-/Spalten-Statistik stören). Das Weißgutter-Flood ist hier
        // komplementär: bei einem echten Splash findet es ebenfalls nichts (1 Box), bei einem
        // verpassten Mehr-Panel-Layout aber das vom Rand erreichbare Gutter-Netz.
        val flood = floodDetect(page)
        if (flood.size in 2..MAX_FLOOD_RESCUE) return flood
        // Sonst das Profil-Ergebnis behalten (1 Panel = Splash). Nur wenn das Profil GAR nichts fand
        // (blanke/synthetische Fläche), liefert das Flood das einzelne Vollseiten-Panel.
        return profile.ifEmpty { flood }
    }

    /**
     * Merge-Pass: verschmilzt benachbarte Kacheln, deren gemeinsame Kante Content-Kontinuität zeigt
     * (dieselbe Szene läuft weiter = Over-Split an interner Struktur wie Stahlträger/Gebäudekante,
     * KEIN echter Gutter). Über einen echten Gutter unterscheiden sich die zwei Szenen → niedrige
     * Korrelation, bleibt getrennt. Gemessen gegen Hand-GT: Precision +~0.04 bei kleinem Recall-Preis.
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

    /** Benachbart (gemeinsame Kante + ≥60% Überlappung) UND Content-kontinuierlich über die Kante. */
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

    /** Pearson-Korrelation der mittleren Luminanz-Profile der [band] Pixel beiderseits der Kante. */
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
     * Kombinierter Profil-XY-Cut. Die Guillotine-Kacheln sind konstruktionsbedingt disjunkt, daher
     * KEINE Containment-Nachfilter (die würden hier nur valide Panels kosten — gemessen ~0.09 Recall).
     */
    private fun gutterProfileDetect(page: RenderedPage): List<PanelRect> =
        GutterProfileCut.detect(page)

    /** Weißgutter-Flood-Pfad (Edge-Seed-Flood → Komponenten → Rahmen-Split) inkl. Nachfilter. */
    private fun floodDetect(page: RenderedPage): List<PanelRect> {
        val threshold = ImageBinarization.otsuThreshold(page)
        val background = ImageBinarization.backgroundMask(page, threshold)
        val flooded = GutterFill.floodFromEdges(background, page.width, page.height)
        val regions = RegionLabeling.labelRegions(flooded, page.width, page.height)

        val minArea = page.width.toLong() * page.height * minPanelAreaFraction
        val filtered = regions.filter { it.width.toLong() * it.height >= minArea }

        val darkMask = BooleanArray(background.size) { !background[it] }
        // Seitenüberspannende Komponenten (schwarze Rahmengitter ohne Weißgutter) aufteilen
        val expanded = filtered.flatMap { box ->
            val wide = box.width > page.width * 0.6 && box.height > page.height * 0.5
            val bandlike = box.width > page.width * 0.85 || box.height > page.height * 0.85
            if (wide || bandlike) BorderLineSplit.split(darkMask, page.width, page.height, box) else listOf(box)
        }
        val sized = expanded.filter { it.width.toLong() * it.height >= minArea }
        return postFilter(sized, darkMask, page)
    }

    /** Gemeinsame Nachfilter beider Pfade: solide Blobs, enthaltene Blasen, Überlappungen. */
    private fun postFilter(boxes: List<PanelRect>, dark: BooleanArray, page: RenderedPage): List<PanelRect> {
        val deSolid = dropSolidBlobs(boxes, dark, page.width, page.height)
        val deBubbled = dropContainedSmall(deSolid, page.width, page.height)
        return dropContained(deBubbled)
    }

    /**
     * Verwirft kleine, fast vollständig dunkle Boxen (Soundeffekt-Buchstaben wie „BLAM",
     * solide Silhouetten) — die sind keine Panels. Echte (auch kleine) Panels haben helle
     * Flächen/Sprechblasen, ihr Dunkelanteil bleibt unter [minDarkFill]. Flächen-gegatet
     * ([maxAreaFraction]), damit nur kleine Blobs betroffen sind, nie ganze Panels.
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
     * Verwirft kleine Boxen (< [smallAreaFraction] Seitenfläche), die vollständig in einer
     * größeren liegen (Sprechblasen).
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

    /** Entfernt Boxen, die zu [containmentFraction] in einer anderen (größeren) liegen. */
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
