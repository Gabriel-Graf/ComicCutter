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
 * Smoke-Integration gegen das ECHTE INT8-Modell (nicht im Repo). Modellpfad via
 * Env `PANEL_ONNX_MODEL`, sonst der lokale Spike-Pfad. Fehlt das Modell, überspringt
 * sich der Test (CI ohne Modell bleibt grün) — daher kein Assert-Fail, sondern `return`.
 */
class OnnxModelRunnerIntegrationTest {

    private val modelPath: String =
        System.getenv("PANEL_ONNX_MODEL")
            ?: "${System.getProperty("user.home")}/Documents/Projekte/komga-yolo-spike/yolo/runs/panel_v3/weights/best.int8.onnx"

    @Test
    fun erkennt_mehrere_panels_auf_einer_echten_seite() {
        val model = File(modelPath)
        if (!model.exists()) {
            println("[skip] Modell nicht gefunden: $modelPath")
            return
        }
        val image = File("../demo/dist/comics/01-pepper-carrot-ep06-p01.jpg")
        if (!image.exists()) {
            println("[skip] Testbild nicht gefunden (demo/dist ist gitignored): ${image.path}")
            return
        }
        val page = loadPage(image)

        OnnxModelRunner(model.readBytes()).use { runner ->
            val source = MlPanelSource(runner, MlFilter(minScore = 0.25f, nmsIoU = 0.7f))
            val guide = PanelGuide(source).guide(page)

            println("[onnx] erkannte Schritte: ${guide.steps.size}, isFullPage=${guide.isFullPage}")
            assertTrue(guide.steps.isNotEmpty(), "Pipeline lieferte keine Schritte")
            assertTrue(!guide.isFullPage && guide.steps.size >= 2, "erwartet ≥2 Panels auf einer Mehr-Panel-Seite")
        }
    }

    /** JPG → host-unabhängige RenderedPage (ARGB-IntArray). Nur im jvm-Testmodul (AWT erlaubt). */
    private fun loadPage(file: File): RenderedPage {
        val img = ImageIO.read(file)
            ?: error("Bild nicht lesbar: ${file.path}")
        val w = img.width
        val h = img.height
        val px = IntArray(w * h)
        img.getRGB(0, 0, w, h, px, 0, w)
        return RenderedPage(w, h, px)
    }
}
