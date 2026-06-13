package com.panela.comiccutter

import com.panela.comiccutter.model.RenderedPage

/**
 * Source of raw panel boxes for a page. Implementations: [GeometricPanelSource]
 * (detector) or [MlPanelSource] (model). The order is determined by [PanelGuide].
 */
fun interface PanelSource {
    fun detect(page: RenderedPage): List<PanelRect>
}
