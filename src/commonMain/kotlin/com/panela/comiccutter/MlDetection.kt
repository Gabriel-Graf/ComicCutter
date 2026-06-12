package com.panela.comiccutter

import com.panela.comiccutter.model.RenderedPage

/** Eine rohe Modell-Detektion vor Filterung, in Seiten-Pixel-Koordinaten. */
data class RawDetection(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val score: Float,
    val cls: Int,
)

/**
 * Führt das Panel-Modell aus. Implementiert von der App (eigene Inferenz) ODER vom
 * optionalen Modul comic-cutter-onnx-jvm. Der Kern bleibt dadurch Native-frei.
 */
interface ModelRunner {
    fun infer(page: RenderedPage): List<RawDetection>
}
