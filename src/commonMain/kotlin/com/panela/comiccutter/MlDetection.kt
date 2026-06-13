package com.panela.comiccutter

import com.panela.comiccutter.model.RenderedPage

/** A raw model detection before filtering, in page pixel coordinates. */
data class RawDetection(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val score: Float,
    val cls: Int,
)

/**
 * Runs the panel model. Implemented either by the app (its own inference) OR by the
 * optional comic-cutter-onnx-jvm module. This keeps the core free of native code.
 */
interface ModelRunner {
    fun infer(page: RenderedPage): List<RawDetection>
}
