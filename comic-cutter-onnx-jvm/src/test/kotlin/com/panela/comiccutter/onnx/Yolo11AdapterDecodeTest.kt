package com.panela.comiccutter.onnx

import kotlin.test.Test
import kotlin.test.assertEquals

class Yolo11AdapterDecodeTest {

    private val lb = Letterbox.of(srcW = 200, srcH = 100, target = 1024)

    @Test
    fun dekodiert_zentrums_box_in_seiten_pixel() {
        // Eine Detektion: Modellraum-Mitte (512,512), w=5.12·20, h=5.12·10 → Seiten-Box [90,45,20,10].
        val out = rows(cx = 512f, cy = 512f, w = 5.12f * 20, h = 5.12f * 10, score = 0.9f)

        val dets = Yolo11Adapter().decodeRows(out, lb)

        assertEquals(1, dets.size)
        val d = dets.first()
        assertEquals(90, d.x)
        assertEquals(45, d.y)
        assertEquals(20, d.width)
        assertEquals(10, d.height)
        assertEquals(0.9f, d.score)
        assertEquals(0, d.cls)
    }

    @Test
    fun verwirft_score_null_detektionen() {
        // Zwei Spalten: erste score 0.8, zweite score 0 → nur die erste überlebt (Score>0-Guard).
        val out = arrayOf(
            floatArrayOf(512f, 512f),   // cx
            floatArrayOf(512f, 512f),   // cy
            floatArrayOf(100f, 100f),   // w
            floatArrayOf(50f, 50f),     // h
            floatArrayOf(0.8f, 0f),     // score
        )

        val dets = Yolo11Adapter().decodeRows(out, lb)

        assertEquals(1, dets.size)
        assertEquals(0.8f, dets.first().score)
    }

    /** Baut den YOLO11-Output `(5, 1)` für genau eine Detektion. */
    private fun rows(cx: Float, cy: Float, w: Float, h: Float, score: Float): Array<FloatArray> =
        arrayOf(
            floatArrayOf(cx),
            floatArrayOf(cy),
            floatArrayOf(w),
            floatArrayOf(h),
            floatArrayOf(score),
        )
}
