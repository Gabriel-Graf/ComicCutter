package com.panela.comiccutter.onnx

import ai.onnxruntime.OrtSession
import com.panela.comiccutter.RawDetection
import com.panela.comiccutter.model.RenderedPage

/**
 * [OnnxAdapter] for a YOLO11 single-class panel model with output head `(1, 4+nc, N)`
 * (nc=1 → `(1,5,N)`: rows cx,cy,w,h,score). Input `(1,3,size,size)`, RGB, `/255`, NCHW,
 * aspect-preserving letterbox with pad color 114. Mirrors the reference `adapter.py`
 * (parity test confirms IoU ≥ 0.95). The conf threshold and NMS are done by the `MlFilter`,
 * not here.
 */
class Yolo11Adapter(override val inputSize: Int = 1024) : OnnxAdapter {

    override val inputShape: LongArray = longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())

    override fun preprocess(page: RenderedPage, lb: Letterbox): FloatArray = renderLetterboxCHW(page, lb)

    override fun decode(result: OrtSession.Result, lb: Letterbox): List<RawDetection> {
        @Suppress("UNCHECKED_CAST")
        val out = (result[0].value as Array<Array<FloatArray>>)[0]   // (4+nc, N)
        return decodeRows(out, lb)
    }

    /** Output rows `(5, N)` → raw pixel boxes. Score>0 guard; everything else is up to the MlFilter. */
    internal fun decodeRows(out: Array<FloatArray>, lb: Letterbox): List<RawDetection> {
        val n = out[0].size
        val dets = ArrayList<RawDetection>(n)
        for (i in 0 until n) {
            val score = out[4][i]
            if (score <= 0f) continue
            val b = lb.boxFromCenter(out[0][i], out[1][i], out[2][i], out[3][i])
            dets.add(RawDetection(b[0], b[1], b[2], b[3], score, cls = 0))
        }
        return dets
    }

    /** Page → CHW float [3·size·size], RGB 0..1, aspect-preserving with gray padding (114/255). */
    private fun renderLetterboxCHW(page: RenderedPage, lb: Letterbox): FloatArray {
        val size = inputSize
        val plane = size * size
        val out = FloatArray(3 * plane) { 114f / 255f }
        for (ty in 0 until size) {
            val sy = lb.sourceY(ty)
            if (sy < 0 || sy >= page.height) continue
            for (tx in 0 until size) {
                val sx = lb.sourceX(tx)
                if (sx < 0 || sx >= page.width) continue
                val p = page.pixels[sy * page.width + sx]
                val idx = ty * size + tx
                out[idx] = ((p shr 16) and 0xFF) / 255f          // R
                out[plane + idx] = ((p shr 8) and 0xFF) / 255f   // G
                out[2 * plane + idx] = (p and 0xFF) / 255f        // B
            }
        }
        return out
    }
}
