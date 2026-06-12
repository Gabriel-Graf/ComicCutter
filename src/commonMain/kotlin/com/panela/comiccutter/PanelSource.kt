package com.panela.comiccutter

import com.panela.comiccutter.model.RenderedPage

/**
 * Quelle roher Panel-Boxen einer Seite. Implementierungen: [GeometricPanelSource]
 * (Detektor) oder [MlPanelSource] (Modell). Die Reihenfolge bestimmt der [GuidedReader].
 */
fun interface PanelSource {
    fun detect(page: RenderedPage): List<PanelRect>
}
