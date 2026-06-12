package com.panela.comiccutter.onnx.cli

import com.panela.comiccutter.onnx.AdapterRegistry

/**
 * Geparste CLI-Argumente. Reine Datenstruktur + reiner [parse] — keine IO, damit der Parser
 * ohne Modell/Bild testbar ist. Validierung (fehlende Pflichtargumente, kaputte Zahlen) wirft
 * [IllegalArgumentException]; die Shell ([Main]) fängt und gibt die Usage aus.
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
            comic-cutter-onnx — Panel-Detektion mit einem ONNX-Modell (offline-fähig).

            Pflicht:
              --model <spec>    Lokaler Pfad | hf:org/repo/datei.onnx | https://…
              --image <pfad>    Eingabe-Seite (jpg/png/…)

            Optional:
              --adapter <name>  Output-Adapter (Default: yolo11). Bekannt: ${AdapterRegistry.names().joinToString(", ")}
              --imgsz <int>     Letterbox-Kantenlänge (Default: ${AdapterRegistry.DEFAULT_INPUT_SIZE})
              --conf <float>    Score-Schwelle (Default: 0.25)
              --nms <float>     NMS-IoU (Default: 0.7)
              --rtl             Manga-Lesereihenfolge (rechts→links)
              --overlay <pfad>  Overlay-PNG mit nummerierten Boxen schreiben

            Ausgabe: Panels als JSON-Array nach stdout.
        """.trimIndent()

        fun parse(args: Array<String>): CliArgs {
            val map = HashMap<String, String>()
            var rtl = false
            var i = 0
            while (i < args.size) {
                when (val a = args[i]) {
                    "--rtl" -> rtl = true
                    "--model", "--image", "--adapter", "--imgsz", "--conf", "--nms", "--overlay" -> {
                        require(i + 1 < args.size) { "Argument $a erwartet einen Wert." }
                        map[a] = args[++i]
                    }
                    else -> throw IllegalArgumentException("Unbekanntes Argument: $a")
                }
                i++
            }

            val model = requireNotNull(map["--model"]) { "--model fehlt." }
            val image = requireNotNull(map["--image"]) { "--image fehlt." }

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
            toIntOrNull() ?: throw IllegalArgumentException("$flag erwartet eine Ganzzahl, war: '$this'")

        private fun String.toFloatValue(flag: String): Float =
            toFloatOrNull() ?: throw IllegalArgumentException("$flag erwartet eine Zahl, war: '$this'")
    }
}
