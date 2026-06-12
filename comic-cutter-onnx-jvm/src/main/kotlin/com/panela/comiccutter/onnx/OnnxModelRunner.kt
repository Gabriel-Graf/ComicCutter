package com.panela.comiccutter.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.panela.comiccutter.ModelRunner
import com.panela.comiccutter.RawDetection
import com.panela.comiccutter.model.RenderedPage
import java.nio.FloatBuffer

/**
 * [ModelRunner] auf ONNX-Runtime für ein YOLO11 single-class Panel-Modell mit Output-Head
 * `(1, 4+nc, N)` (nc=1 → `(1,5,N)`: Zeilen cx,cy,w,h,score). Rendert die Seite ins
 * [inputSize]-Letterbox-Quadrat (RGB, /255, NCHW, Pad-Farbe 114), inferiert und dekodiert
 * rohe Detektionen in Seiten-Pixel-Koordinaten (cls=0). conf-Threshold und NMS macht der
 * [com.panela.comiccutter.MlFilter] davor/danach — dieser Runner filtert nicht.
 *
 * Das Modell ist NICHT Teil der Lib: [modelBytes] reicht der Konsument zur Laufzeit rein.
 */
class OnnxModelRunner(
    modelBytes: ByteArray,
    private val inputSize: Int = 1024,
) : ModelRunner, AutoCloseable {

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(modelBytes, OrtSession.SessionOptions())
    private val inputName = session.inputNames.first()

    override fun infer(page: RenderedPage): List<RawDetection> {
        val lb = Letterbox.of(page.width, page.height, inputSize)
        val chw = renderLetterboxCHW(page, lb)
        OnnxTensor.createTensor(
            env, FloatBuffer.wrap(chw), longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong()),
        ).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val out = (result[0].value as Array<Array<FloatArray>>)[0]   // (4+nc, N)
                return decode(out, lb)
            }
        }
    }

    /** Output `(5, N)` → rohe Pixel-Boxen. Score>0-Guard; alles Weitere macht MlFilter. */
    private fun decode(out: Array<FloatArray>, lb: Letterbox): List<RawDetection> {
        val n = out[0].size
        val dets = ArrayList<RawDetection>()
        for (i in 0 until n) {
            val score = out[4][i]
            if (score <= 0f) continue
            val b = lb.boxFromCenter(out[0][i], out[1][i], out[2][i], out[3][i])
            dets.add(RawDetection(b[0], b[1], b[2], b[3], score, cls = 0))
        }
        return dets
    }

    /** Seite → CHW-Float [3·size·size], RGB 0..1, aspect-erhaltend mit grauem Padding (114/255). */
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

    override fun close() {
        session.close()
    }
}
