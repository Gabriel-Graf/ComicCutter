package com.panela.comiccutter.onnx

import com.panela.comiccutter.MlFilter
import com.panela.comiccutter.MlPanelSource
import com.panela.comiccutter.PanelRect
import com.panela.comiccutter.model.RenderedPage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Numerischer Paritäts-Test: Kotlin OnnxModelRunner vs. Python adapter.py.
 *
 * Überprüft dass beide Seiten auf demselben Bild mit demselben Modell (best.int8.onnx)
 * identische Panel-Boxen liefern. Kriterium: gleiche Anzahl + jede gematchte Box-Pair hat
 * IoU ≥ 0.95 (int-Rundung → ~0.99 erwartet).
 *
 * Skip-Bedingungen (CI ohne lokale Ressourcen bleibt grün):
 * - Modelldatei fehlt
 * - Testbild fehlt
 * - Python / onnxruntime / cv2 nicht verfügbar
 */
class OnnxModelRunnerParityTest {

    /** Env PANEL_ONNX_MODEL oder Fallback auf mllabeltool-Modellpfad. */
    private val modelPath: String =
        System.getenv("PANEL_ONNX_MODEL")
            ?: "${System.getProperty("user.home")}/Documents/Projekte/mllabeltool/models/yolo_v3/best.int8.onnx"

    /** Modellverzeichnis (enthält adapter.py). */
    private val modelDir: String = File(modelPath).parent ?: ""

    /** Wrapper-Script (neben dieser Datei im parity/-Verzeichnis). */
    private val wrapperScript: String =
        "${System.getProperty("user.home")}/Documents/Projekte/GuidedComic/comic-cutter-onnx-jvm/parity/run_adapter.py"

    /** Bild: dasselbe für beide Seiten. */
    private val imagePath: String =
        "${System.getProperty("user.home")}/Documents/Projekte/GuidedComic/demo/dist/comics/01-pepper-carrot-ep06-p01.jpg"

    /**
     * Python-Interpreter: Env PARITY_PYTHON, sonst mllabeltool-.venv, sonst python3.
     */
    private val pythonInterpreter: String =
        System.getenv("PARITY_PYTHON")
            ?: run {
                val venv = "${System.getProperty("user.home")}/Documents/Projekte/mllabeltool/.venv/bin/python"
                if (File(venv).exists()) venv else "python3"
            }

    @Test
    fun kotlinUndPythonLiefernGleicheBoxen() {
        // --- Skip-Bedingungen ---
        val modelFile = File(modelPath)
        if (!modelFile.exists()) {
            println("[parity-skip] Modell nicht gefunden: $modelPath")
            return
        }
        val imageFile = File(imagePath)
        if (!imageFile.exists()) {
            println("[parity-skip] Testbild nicht gefunden: $imagePath")
            return
        }
        val wrapperFile = File(wrapperScript)
        if (!wrapperFile.exists()) {
            println("[parity-skip] Wrapper-Script nicht gefunden: $wrapperScript")
            return
        }
        if (!pythonHasDeps()) {
            println("[parity-skip] Python-Deps (onnxruntime/numpy/cv2) nicht verfügbar unter: $pythonInterpreter")
            return
        }

        // --- Python-Seite ---
        val pyBoxes = runPythonAdapter()
        println("[parity] Python-Boxen (${pyBoxes.size}):")
        pyBoxes.forEachIndexed { i, b -> println("  py[$i] x=${b[0]}, y=${b[1]}, w=${b[2]}, h=${b[3]}, score=${b[4]}") }

        // --- Kotlin-Seite ---
        val ktBoxes = runKotlinDetector(modelFile, imageFile)
        println("[parity] Kotlin-Boxen (${ktBoxes.size}):")
        ktBoxes.forEachIndexed { i, b -> println("  kt[$i] x=${b.x}, y=${b.y}, w=${b.width}, h=${b.height}") }

        // --- Paritäts-Prüfung ---
        val pyCount = pyBoxes.size
        val ktCount = ktBoxes.size

        if (pyCount != ktCount) {
            val msg = buildString {
                append("PARITY FAIL: Python lieferte $pyCount Boxen, Kotlin $ktCount Boxen.\n")
                append("Python: ${pyBoxes.map { "[${it[0]},${it[1]},${it[2]},${it[3]}]" }}\n")
                append("Kotlin: ${ktBoxes.map { "[${it.x},${it.y},${it.width},${it.height}]" }}")
            }
            println(msg)
            assertEquals(pyCount, ktCount, msg)
        }

        // Greedy-IoU-Match: für jede Python-Box die Kotlin-Box mit höchstem IoU
        val matched = greedyMatch(pyBoxes, ktBoxes)
        val ious = matched.map { (py, kt) ->
            val iou = boxIoU(
                py[0], py[1], py[2], py[3],
                kt.x.toDouble(), kt.y.toDouble(), kt.width.toDouble(), kt.height.toDouble()
            )
            Triple(py, kt, iou)
        }

        println("[parity] Matched pairs mit IoU:")
        ious.forEachIndexed { i, (py, kt, iou) ->
            println("  [$i] py=[${py[0]},${py[1]},${py[2]},${py[3]}] ↔ kt=[${kt.x},${kt.y},${kt.width},${kt.height}]  IoU=${"%.4f".format(iou)}")
        }

        val worstIou = ious.minOfOrNull { it.third } ?: 1.0
        println("[parity] Schlechtester IoU: ${"%.4f".format(worstIou)} (Schwelle: 0.95)")

        val failPairs = ious.filter { it.third < 0.95 }
        if (failPairs.isNotEmpty()) {
            val msg = buildString {
                append("PARITY FAIL: ${failPairs.size} Boxen unter IoU 0.95.\n")
                failPairs.forEach { (py, kt, iou) ->
                    append("  IoU=${"%.4f".format(iou)}  py=[${py[0]},${py[1]},${py[2]},${py[3]}] ↔ kt=[${kt.x},${kt.y},${kt.width},${kt.height}]\n")
                }
                append("Python gesamt: ${pyBoxes.map { "[${it[0]},${it[1]},${it[2]},${it[3]}]" }}\n")
                append("Kotlin gesamt: ${ktBoxes.map { "[${it.x},${it.y},${it.width},${it.height}]" }}")
            }
            println(msg)
            assertTrue(failPairs.isEmpty(), msg)
        }

        println("[parity] PASS — alle ${ious.size} Boxen, schlechtester IoU=${"%.4f".format(worstIou)} ≥ 0.95")
    }

    private fun pythonHasDeps(): Boolean {
        return try {
            val proc = ProcessBuilder(pythonInterpreter, "-c", "import onnxruntime, numpy, cv2")
                .redirectErrorStream(true)
                .start()
            proc.waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

    /** Ruft run_adapter.py auf und parst die JSON-Ausgabe als List<DoubleArray(5)>. */
    private fun runPythonAdapter(): List<DoubleArray> {
        val proc = ProcessBuilder(pythonInterpreter, wrapperScript, modelDir, imagePath)
            .redirectErrorStream(false)
            .start()
        val stdout = proc.inputStream.bufferedReader().readText().trim()
        val stderr = proc.errorStream.bufferedReader().readText().trim()
        val exit = proc.waitFor()
        if (exit != 0) {
            error("run_adapter.py fehlgeschlagen (exit $exit).\nSTDERR: $stderr\nSTDOUT: $stdout")
        }
        return parseJsonArray(stdout)
    }

    /** Einfacher JSON-Parser für [[x,y,w,h,s],...] ohne externe Deps. */
    private fun parseJsonArray(json: String): List<DoubleArray> {
        // Entferne äußere Klammern, split nach "]," um innere Arrays zu trennen
        val trimmed = json.trim().removeSurrounding("[", "]").trim()
        if (trimmed.isEmpty()) return emptyList()
        // Split an ], [ Grenzen
        val entries = trimmed.split(Regex("""\]\s*,\s*\["""))
        return entries.map { entry ->
            val nums = entry.trim().trim('[', ']').split(",")
            nums.map { it.trim().toDouble() }.toDoubleArray()
        }
    }

    private fun runKotlinDetector(modelFile: File, imageFile: File): List<PanelRect> {
        OnnxModelRunner(modelFile.readBytes()).use { runner ->
            val source = MlPanelSource(runner, MlFilter(minScore = 0.25f, nmsIoU = 0.7f))
            val page = loadPage(imageFile)
            return source.detect(page)
        }
    }

    private fun loadPage(file: File): RenderedPage {
        val img = ImageIO.read(file) ?: error("Bild nicht lesbar: ${file.path}")
        val w = img.width
        val h = img.height
        val px = IntArray(w * h)
        img.getRGB(0, 0, w, h, px, 0, w)
        return RenderedPage(w, h, px)
    }

    /**
     * Greedy-IoU-Match: für jede Python-Box die noch nicht gematchte Kotlin-Box mit höchstem IoU.
     * Gibt Paare (pyBox, ktBox) zurück.
     */
    private fun greedyMatch(
        pyBoxes: List<DoubleArray>,
        ktBoxes: List<PanelRect>,
    ): List<Pair<DoubleArray, PanelRect>> {
        val remaining = ktBoxes.toMutableList()
        return pyBoxes.map { py ->
            val best = remaining.maxByOrNull { kt ->
                boxIoU(py[0], py[1], py[2], py[3], kt.x.toDouble(), kt.y.toDouble(), kt.width.toDouble(), kt.height.toDouble())
            } ?: error("Keine Kotlin-Box mehr zum Matchen")
            remaining.remove(best)
            Pair(py, best)
        }
    }

    /** IoU zweier Boxen im Format (x, y, width, height). */
    private fun boxIoU(ax: Double, ay: Double, aw: Double, ah: Double, bx: Double, by: Double, bw: Double, bh: Double): Double {
        val ix = maxOf(ax, bx)
        val iy = maxOf(ay, by)
        val iw = minOf(ax + aw, bx + bw) - ix
        val ih = minOf(ay + ah, by + bh) - iy
        if (iw <= 0 || ih <= 0) return 0.0
        val inter = iw * ih
        val union = aw * ah + bw * bh - inter
        return if (union <= 0) 0.0 else inter / union
    }
}
