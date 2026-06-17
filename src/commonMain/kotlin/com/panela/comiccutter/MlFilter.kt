package com.panela.comiccutter

/**
 * Pure filter pipeline from model output to panel boxes: conf threshold → optional
 * class filter → NMS (IoU) → min area. Deterministic, testable without a model.
 *
 * @param minScore         Detections below this score are discarded.
 * @param nmsIoU           Two boxes with IoU >= this value are treated as the same; the weaker one is dropped.
 * @param minAreaFraction  Boxes smaller than this fraction of the page area are discarded.
 * @param keepClass        If set: keep only detections of this class.
 */
data class MlFilter(
    val minScore: Float = 0.30f,
    val nmsIoU: Float = 0.50f,
    val minAreaFraction: Float = 0.01f,
    val keepClass: Int? = null,
) {
    fun apply(detections: List<RawDetection>, pageW: Int, pageH: Int): List<PanelRect> {
        val minArea = pageW.toLong() * pageH * minAreaFraction
        val kept = detections
            .filter { it.score >= minScore }
            .filter { keepClass == null || it.cls == keepClass }
            .filter { it.width.toLong() * it.height >= minArea }
            .sortedByDescending { it.score }
        return nms(kept).map { PanelRect(it.x, it.y, it.width, it.height, it.score) }
    }

    /** Greedy NMS: strongest first, suppresses every later box with IoU >= [nmsIoU]. */
    private fun nms(sortedByScore: List<RawDetection>): List<RawDetection> {
        val kept = mutableListOf<RawDetection>()
        for (d in sortedByScore) {
            if (kept.none { iou(it, d) >= nmsIoU }) kept.add(d)
        }
        return kept
    }

    private fun iou(a: RawDetection, b: RawDetection): Float {
        val ix = maxOf(a.x, b.x); val iy = maxOf(a.y, b.y)
        val ax = minOf(a.x + a.width, b.x + b.width); val ay = minOf(a.y + a.height, b.y + b.height)
        val iw = ax - ix; val ih = ay - iy
        if (iw <= 0 || ih <= 0) return 0f
        val inter = iw.toLong() * ih
        val union = a.width.toLong() * a.height + b.width.toLong() * b.height - inter
        return if (union <= 0) 0f else inter.toFloat() / union
    }
}
