package com.panela.comiccutter

import com.panela.comiccutter.model.RenderedPage

/**
 * [PanelSource] backed by an ML model: [runner] produces raw detections,
 * [filter] reduces them (conf/NMS/min-area) to panel boxes. The order is
 * determined by [PanelGuide] — here the boxes come unordered.
 */
class MlPanelSource(
    private val runner: ModelRunner,
    private val filter: MlFilter = MlFilter(),
) : PanelSource {
    override fun detect(page: RenderedPage): List<PanelRect> =
        filter.apply(runner.infer(page), page.width, page.height)
}
