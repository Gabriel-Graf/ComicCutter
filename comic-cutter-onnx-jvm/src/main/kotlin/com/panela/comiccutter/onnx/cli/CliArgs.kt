package com.panela.comiccutter.onnx.cli

import com.panela.comiccutter.onnx.AdapterRegistry

/**
 * Parsed CLI arguments. Pure data structure + pure [parse] — no IO, so the parser is testable
 * without a model/image. Validation (missing required arguments, malformed numbers) throws
 * [IllegalArgumentException]; the shell ([Main]) catches it and prints the usage.
 */
data class CliArgs(
    val model: String,
    val image: String,
    val adapter: String = "yolo11",
    val inputSize: Int = AdapterRegistry.DEFAULT_INPUT_SIZE,
    val minScore: Float = 0.25f,
    val nmsIoU: Float = 0.7f,
    val rtl: Boolean = false,
    val overlay: String? = null,
) {
    companion object {
        val USAGE: String = """
            comic-cutter-onnx — panel detection with an ONNX model (offline-capable).

            Required:
              --model <spec>    Local path | hf:org/repo/file.onnx | https://…
              --image <path>    Input page (jpg/png/…)

            Optional:
              --adapter <name>  Output adapter (default: yolo11). Known: ${AdapterRegistry.names().joinToString(", ")}
              --imgsz <int>     Letterbox edge length (default: ${AdapterRegistry.DEFAULT_INPUT_SIZE})
              --conf <float>    Score threshold (default: 0.25)
              --nms <float>     NMS IoU (default: 0.7)
              --rtl             Manga reading order (right→left)
              --overlay <path>  Write overlay PNG with numbered boxes

            Output: panels as a JSON array to stdout.
        """.trimIndent()

        fun parse(args: Array<String>): CliArgs {
            val map = HashMap<String, String>()
            var rtl = false
            var i = 0
            while (i < args.size) {
                when (val a = args[i]) {
                    "--rtl" -> rtl = true
                    "--model", "--image", "--adapter", "--imgsz", "--conf", "--nms", "--overlay" -> {
                        require(i + 1 < args.size) { "Argument $a expects a value." }
                        map[a] = args[++i]
                    }
                    else -> throw IllegalArgumentException("Unknown argument: $a")
                }
                i++
            }

            val model = requireNotNull(map["--model"]) { "--model is missing." }
            val image = requireNotNull(map["--image"]) { "--image is missing." }

            return CliArgs(
                model = model,
                image = image,
                adapter = map["--adapter"] ?: "yolo11",
                inputSize = map["--imgsz"]?.toIntValue("--imgsz") ?: AdapterRegistry.DEFAULT_INPUT_SIZE,
                minScore = map["--conf"]?.toFloatValue("--conf") ?: 0.25f,
                nmsIoU = map["--nms"]?.toFloatValue("--nms") ?: 0.7f,
                rtl = rtl,
                overlay = map["--overlay"],
            )
        }

        private fun String.toIntValue(flag: String): Int =
            toIntOrNull() ?: throw IllegalArgumentException("$flag expects an integer, was: '$this'")

        private fun String.toFloatValue(flag: String): Float =
            toFloatOrNull() ?: throw IllegalArgumentException("$flag expects a number, was: '$this'")
    }
}
