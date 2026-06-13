package com.panela.comiccutter.onnx

import com.panela.comiccutter.MlFilter
import com.panela.comiccutter.MlPanelSource
import com.panela.comiccutter.PanelGuide
import com.panela.comiccutter.model.RenderedPage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Smoke integration against the REAL INT8 model (not in the repo). Model path via
 * env `PANEL_ONNX_MODEL`, otherwise the local spike path. If the model is missing, the
 * test skips itself (CI without the model stays green) — hence no assert fail, just `return`.
 */
class OnnxModelRunnerIntegrationTest {

    private val modelPath: String =
        System.getenv("PANEL_ONNX_MODEL")
            ?: "${System.getProperty("user.home")}/Documents/Projekte/komga-yolo-spike/yolo/runs/panel_v3/weights/best.int8.onnx"

    @Test
    fun detects_multiple_panels_on_a_real_page() {
        val model = File(modelPath)
        if (!model.exists()) {
            println("[skip] model not found: $modelPath")
            return
        }
        val image = File("../demo/dist/comics/01-pepper-carrot-ep06-p01.jpg")
        if (!image.exists()) {
            println("[skip] test image not found (demo/dist is gitignored): ${image.path}")
            return
        }
        val page = loadPage(image)

        OnnxModelRunner(model.readBytes()).use { runner ->
            val source = MlPanelSource(runner, MlFilter(minScore = 0.25f, nmsIoU = 0.7f))
            val guide = PanelGuide(source).guide(page)

            println("[onnx] detected steps: ${guide.steps.size}, isFullPage=${guide.isFullPage}")
            assertTrue(guide.steps.isNotEmpty(), "pipeline returned no steps")
            assertTrue(!guide.isFullPage && guide.steps.size >= 2, "expected ≥2 panels on a multi-panel page")
        }
    }

    /** JPG → host-independent RenderedPage (ARGB IntArray). Only in the jvm test module (AWT allowed). */
    private fun loadPage(file: File): RenderedPage {
        val img = ImageIO.read(file)
            ?: error("image not readable: ${file.path}")
        val w = img.width
        val h = img.height
        val px = IntArray(w * h)
        img.getRGB(0, 0, w, h, px, 0, w)
        return RenderedPage(w, h, px)
    }
}
