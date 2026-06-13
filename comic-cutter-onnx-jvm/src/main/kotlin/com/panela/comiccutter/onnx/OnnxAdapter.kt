package com.panela.comiccutter.onnx

import ai.onnxruntime.OrtSession
import com.panela.comiccutter.RawDetection
import com.panela.comiccutter.model.RenderedPage

/**
 * Model-specific seam for [OnnxModelRunner]: encapsulates the input geometry, preprocessing and
 * output decode of a concrete ONNX model. The runner only handles the session plumbing (loading,
 * `session.run`, tensor lifecycle) and delegates everything model-specific here.
 *
 * This lets a different community model be plugged in — different input size, NHWC instead of
 * NCHW, BGR instead of RGB, a different output layout (xyxy, transposed, with objectness) — via a
 * new implementation, without changing the runner and without Python at runtime. Registration
 * happens through the [AdapterRegistry].
 */
interface OnnxAdapter {
    /** Edge length of the square letterbox input (e.g. 1024). */
    val inputSize: Int

    /** Shape of the input tensor, e.g. `(1,3,size,size)` for NCHW-RGB. */
    val inputShape: LongArray

    /** Page → flat float tensor matching [inputShape] (letterbox-scaled via [lb]). */
    fun preprocess(page: RenderedPage, lb: Letterbox): FloatArray

    /** Raw session output → detections in page-pixel coordinates, before any filtering. */
    fun decode(result: OrtSession.Result, lb: Letterbox): List<RawDetection>
}
