package com.panela.comiccutter

import com.panela.comiccutter.model.RenderedPage

/** [PanelSource] based on the geometric [PanelDetector]. Comics: left-to-right. */
class GeometricPanelSource(
    private val detector: PanelDetector = PanelDetector(),
) : PanelSource {
    override fun detect(page: RenderedPage): List<PanelRect> =
        detector.detect(page, ReadingDirection.LEFT_TO_RIGHT)
}
