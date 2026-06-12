package com.panela.comiccutter.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.panela.comiccutter.ModelRunner
import com.panela.comiccutter.RawDetection
import com.panela.comiccutter.model.RenderedPage
import java.nio.FloatBuffer

/**
 * [ModelRunner] auf ONNX-Runtime. Das Modell-Spezifische (Input-Geometrie, Preprocessing,
 * Output-Decode) liegt im [OnnxAdapter]; dieser Runner macht nur das Session-Plumbing:
 * Session aus [modelBytes] laden, Input-Tensor bauen, `session.run`, Tensor schließen.
 *
 * Default-Adapter ist [Yolo11Adapter] (Festkontrakt YOLO11 single-class). Für ein abweichendes
 * Modell einen eigenen [OnnxAdapter] reichen — kein Python, keine Runner-Änderung.
 *
 * Das Modell ist NICHT Teil der Lib: [modelBytes] reicht der Konsument zur Laufzeit rein.
 */
class OnnxModelRunner(
    modelBytes: ByteArray,
    private val adapter: OnnxAdapter = Yolo11Adapter(),
) : ModelRunner, AutoCloseable {

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(modelBytes, OrtSession.SessionOptions())
    private val inputName = session.inputNames.first()

    override fun infer(page: RenderedPage): List<RawDetection> {
        val lb = Letterbox.of(page.width, page.height, adapter.inputSize)
        val chw = adapter.preprocess(page, lb)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(chw), adapter.inputShape).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                return adapter.decode(result, lb)
            }
        }
    }

    override fun close() {
        session.close()
    }
}
