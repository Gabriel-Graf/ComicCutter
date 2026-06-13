package com.panela.comiccutter.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.panela.comiccutter.ModelRunner
import com.panela.comiccutter.RawDetection
import com.panela.comiccutter.model.RenderedPage
import java.nio.FloatBuffer

/**
 * [ModelRunner] on top of the ONNX Runtime. The model-specific part (input geometry,
 * preprocessing, output decode) lives in the [OnnxAdapter]; this runner only does the session
 * plumbing: load the session from [modelBytes], build the input tensor, `session.run`, close the
 * tensor.
 *
 * The default adapter is [Yolo11Adapter] (fixed contract YOLO11 single-class). For a different
 * model, pass your own [OnnxAdapter] — no Python, no runner change.
 *
 * The model is NOT part of the library: the consumer passes [modelBytes] in at runtime.
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
