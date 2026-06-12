package com.panela.comiccutter

import com.panela.comiccutter.model.RenderedPage

/** [PanelSource] auf Basis des geometrischen [PanelDetector]. Comics: links→rechts. */
class GeometricPanelSource(
    private val detector: PanelDetector = PanelDetector(),
) : PanelSource {
    override fun detect(page: RenderedPage): List<PanelRect> =
        detector.detect(page, ReadingDirection.LEFT_TO_RIGHT)
}
