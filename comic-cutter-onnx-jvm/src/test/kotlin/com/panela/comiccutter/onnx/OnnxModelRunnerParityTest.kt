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
 * Numerical parity test: Kotlin OnnxModelRunner vs. Python adapter.py.
 *
 * Verifies that both sides return identical panel boxes on the same image with the same
 * model (best.int8.onnx). Criterion: same count + every matched box pair has
 * IoU ≥ 0.95 (int rounding → ~0.99 expected).
 *
 * Skip conditions (CI without local resources stays green):
 * - model file missing
 * - test image missing
 * - Python / onnxruntime / cv2 not available
 */
class OnnxModelRunnerParityTest {

    /** Env PANEL_ONNX_MODEL or fallback to the mllabeltool model path. */
    private val modelPath: String =
        System.getenv("PANEL_ONNX_MODEL")
            ?: "${System.getProperty("user.home")}/Documents/Projekte/mllabeltool/models/yolo_v3/best.int8.onnx"

    /** Model directory (contains adapter.py). */
    private val modelDir: String = File(modelPath).parent ?: ""

    /** Wrapper script (next to this file in the parity/ directory). */
    private val wrapperScript: String =
        "${System.getProperty("user.home")}/Documents/Projekte/GuidedComic/comic-cutter-onnx-jvm/parity/run_adapter.py"

    /** Image: the same for both sides. */
    private val imagePath: String =
        "${System.getProperty("user.home")}/Documents/Projekte/GuidedComic/demo/dist/comics/01-pepper-carrot-ep06-p01.jpg"

    /**
     * Python interpreter: env PARITY_PYTHON, otherwise the mllabeltool .venv, otherwise python3.
     */
    private val pythonInterpreter: String =
        System.getenv("PARITY_PYTHON")
            ?: run {
                val venv = "${System.getProperty("user.home")}/Documents/Projekte/mllabeltool/.venv/bin/python"
                if (File(venv).exists()) venv else "python3"
            }

    @Test
    fun kotlin_and_python_return_the_same_boxes() {
        // --- Skip conditions ---
        val modelFile = File(modelPath)
        if (!modelFile.exists()) {
            println("[parity-skip] model not found: $modelPath")
            return
        }
        val imageFile = File(imagePath)
        if (!imageFile.exists()) {
            println("[parity-skip] test image not found: $imagePath")
            return
        }
        val wrapperFile = File(wrapperScript)
        if (!wrapperFile.exists()) {
            println("[parity-skip] wrapper script not found: $wrapperScript")
            return
        }
        if (!pythonHasDeps()) {
            println("[parity-skip] Python deps (onnxruntime/numpy/cv2) not available at: $pythonInterpreter")
            return
        }

        // --- Python side ---
        val pyBoxes = runPythonAdapter()
        println("[parity] Python boxes (${pyBoxes.size}):")
        pyBoxes.forEachIndexed { i, b -> println("  py[$i] x=${b[0]}, y=${b[1]}, w=${b[2]}, h=${b[3]}, score=${b[4]}") }

        // --- Kotlin side ---
        val ktBoxes = runKotlinDetector(modelFile, imageFile)
        println("[parity] Kotlin boxes (${ktBoxes.size}):")
        ktBoxes.forEachIndexed { i, b -> println("  kt[$i] x=${b.x}, y=${b.y}, w=${b.width}, h=${b.height}") }

        // --- Parity check ---
        val pyCount = pyBoxes.size
        val ktCount = ktBoxes.size

        if (pyCount != ktCount) {
            val msg = buildString {
                append("PARITY FAIL: Python returned $pyCount boxes, Kotlin $ktCount boxes.\n")
                append("Python: ${pyBoxes.map { "[${it[0]},${it[1]},${it[2]},${it[3]}]" }}\n")
                append("Kotlin: ${ktBoxes.map { "[${it.x},${it.y},${it.width},${it.height}]" }}")
            }
            println(msg)
            assertEquals(pyCount, ktCount, msg)
        }

        // Greedy IoU match: for each Python box the Kotlin box with the highest IoU
        val matched = greedyMatch(pyBoxes, ktBoxes)
        val ious = matched.map { (py, kt) ->
            val iou = boxIoU(
                py[0], py[1], py[2], py[3],
                kt.x.toDouble(), kt.y.toDouble(), kt.width.toDouble(), kt.height.toDouble()
            )
            Triple(py, kt, iou)
        }

        println("[parity] Matched pairs with IoU:")
        ious.forEachIndexed { i, (py, kt, iou) ->
            println("  [$i] py=[${py[0]},${py[1]},${py[2]},${py[3]}] ↔ kt=[${kt.x},${kt.y},${kt.width},${kt.height}]  IoU=${"%.4f".format(iou)}")
        }

        val worstIou = ious.minOfOrNull { it.third } ?: 1.0
        println("[parity] Worst IoU: ${"%.4f".format(worstIou)} (threshold: 0.95)")

        val failPairs = ious.filter { it.third < 0.95 }
        if (failPairs.isNotEmpty()) {
            val msg = buildString {
                append("PARITY FAIL: ${failPairs.size} boxes below IoU 0.95.\n")
                failPairs.forEach { (py, kt, iou) ->
                    append("  IoU=${"%.4f".format(iou)}  py=[${py[0]},${py[1]},${py[2]},${py[3]}] ↔ kt=[${kt.x},${kt.y},${kt.width},${kt.height}]\n")
                }
                append("Python total: ${pyBoxes.map { "[${it[0]},${it[1]},${it[2]},${it[3]}]" }}\n")
                append("Kotlin total: ${ktBoxes.map { "[${it.x},${it.y},${it.width},${it.height}]" }}")
            }
            println(msg)
            assertTrue(failPairs.isEmpty(), msg)
        }

        println("[parity] PASS — all ${ious.size} boxes, worst IoU=${"%.4f".format(worstIou)} ≥ 0.95")
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

    /** Calls run_adapter.py and parses the JSON output as List<DoubleArray(5)>. */
    private fun runPythonAdapter(): List<DoubleArray> {
        val proc = ProcessBuilder(pythonInterpreter, wrapperScript, modelDir, imagePath)
            .redirectErrorStream(false)
            .start()
        val stdout = proc.inputStream.bufferedReader().readText().trim()
        val stderr = proc.errorStream.bufferedReader().readText().trim()
        val exit = proc.waitFor()
        if (exit != 0) {
            error("run_adapter.py failed (exit $exit).\nSTDERR: $stderr\nSTDOUT: $stdout")
        }
        return parseJsonArray(stdout)
    }

    /** Simple JSON parser for [[x,y,w,h,s],...] without external deps. */
    private fun parseJsonArray(json: String): List<DoubleArray> {
        // Remove outer brackets, split on "]," to separate inner arrays
        val trimmed = json.trim().removeSurrounding("[", "]").trim()
        if (trimmed.isEmpty()) return emptyList()
        // Split at ], [ boundaries
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
        val img = ImageIO.read(file) ?: error("image not readable: ${file.path}")
        val w = img.width
        val h = img.height
        val px = IntArray(w * h)
        img.getRGB(0, 0, w, h, px, 0, w)
        return RenderedPage(w, h, px)
    }

    /**
     * Greedy IoU match: for each Python box the not-yet-matched Kotlin box with the highest IoU.
     * Returns pairs (pyBox, ktBox).
     */
    private fun greedyMatch(
        pyBoxes: List<DoubleArray>,
        ktBoxes: List<PanelRect>,
    ): List<Pair<DoubleArray, PanelRect>> {
        val remaining = ktBoxes.toMutableList()
        return pyBoxes.map { py ->
            val best = remaining.maxByOrNull { kt ->
                boxIoU(py[0], py[1], py[2], py[3], kt.x.toDouble(), kt.y.toDouble(), kt.width.toDouble(), kt.height.toDouble())
            } ?: error("no Kotlin box left to match")
            remaining.remove(best)
            Pair(py, best)
        }
    }

    /** IoU of two boxes in the format (x, y, width, height). */
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
