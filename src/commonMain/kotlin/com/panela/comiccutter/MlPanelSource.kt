package com.panela.comiccutter

import com.panela.comiccutter.model.RenderedPage

/**
 * [PanelSource] auf Basis eines ML-Modells: [runner] erzeugt rohe Detektionen,
 * [filter] reduziert sie (conf/NMS/min-area) auf Panel-Boxen. Die Reihenfolge
 * bestimmt der [GuidedReader] — hier kommen die Boxen ungeordnet.
 */
class MlPanelSource(
    private val runner: ModelRunner,
    private val filter: MlFilter = MlFilter(),
) : PanelSource {
    override fun detect(page: RenderedPage): List<PanelRect> =
        filter.apply(runner.infer(page), page.width, page.height)
}
