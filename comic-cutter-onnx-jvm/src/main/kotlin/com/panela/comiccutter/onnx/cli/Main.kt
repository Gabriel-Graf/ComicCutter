package com.panela.comiccutter.onnx.cli

import com.panela.comiccutter.MlFilter
import com.panela.comiccutter.MlPanelSource
import com.panela.comiccutter.PanelRect
import com.panela.comiccutter.ReadingDirection
import com.panela.comiccutter.ReadingOrder
import com.panela.comiccutter.model.RenderedPage
import com.panela.comiccutter.onnx.AdapterRegistry
import com.panela.comiccutter.onnx.ModelLocator
import com.panela.comiccutter.onnx.OnnxModelRunner
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.system.exitProcess

/**
 * CLI-Einstieg: ein ONNX-Modell (lokal | hf: | https) gegen ein Bild laufen lassen und die
 * Panels als JSON ausgeben — offline-fähig, ohne Python. Die reine Argument-/URL-Logik liegt
 * in [CliArgs] bzw. [ModelLocator]; hier passiert nur IO (Modell laden, Bild lesen, PNG schreiben).
 */
object Main {

    @JvmStatic
    fun main(rawArgs: Array<String>) {
        val args = try {
            CliArgs.parse(rawArgs)
        } catch (e: IllegalArgumentException) {
            System.err.println("Fehler: ${e.message}\n\n${CliArgs.USAGE}")
            exitProcess(1)
        }

        val page = loadPage(File(args.image))
        val adapter = AdapterRegistry.create(args.adapter, args.inputSize)
        val modelBytes = ModelLocator.resolveBytes(args.model)

        val panels = OnnxModelRunner(modelBytes, adapter).use { runner ->
            val source = MlPanelSource(runner, MlFilter(minScore = args.minScore, nmsIoU = args.nmsIoU))
            val direction = if (args.rtl) ReadingDirection.RIGHT_TO_LEFT else ReadingDirection.LEFT_TO_RIGHT
            ReadingOrder.sort(source.detect(page), direction)
        }

        println(toJson(panels))

        args.overlay?.let { writeOverlay(File(args.image), panels, File(it)) }
    }

    /** Geordnete Panels → JSON-Array `[{order,x,y,width,height}, …]` in Seiten-Pixel-Koordinaten. */
    private fun toJson(panels: List<PanelRect>): String =
        panels.mapIndexed { i, p ->
            """{"order":${i + 1},"x":${p.x},"y":${p.y},"width":${p.width},"height":${p.height}}"""
        }.joinToString(prefix = "[", postfix = "]", separator = ",")

    private fun loadPage(file: File): RenderedPage {
        require(file.isFile) { "Bild nicht gefunden: ${file.path}" }
        val img = ImageIO.read(file) ?: error("Bild nicht lesbar: ${file.path}")
        val w = img.width
        val h = img.height
        val px = IntArray(w * h)
        img.getRGB(0, 0, w, h, px, 0, w)
        return RenderedPage(w, h, px)
    }

    /** Quellbild kopieren, nummerierte Boxen einzeichnen, als PNG schreiben. */
    private fun writeOverlay(source: File, panels: List<PanelRect>, target: File) {
        val src = ImageIO.read(source) ?: error("Bild nicht lesbar: ${source.path}")
        val canvas = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_RGB)
        val g = canvas.createGraphics()
        g.drawImage(src, 0, 0, null)
        g.color = Color(0x2E, 0xC4, 0xB6)
        g.stroke = BasicStroke(maxOf(2f, src.width / 400f))
        g.font = Font("SansSerif", Font.BOLD, maxOf(16, src.width / 40))
        panels.forEachIndexed { i, p ->
            g.drawRect(p.x, p.y, p.width, p.height)
            g.drawString("${i + 1}", p.x + 6, p.y + g.font.size)
        }
        g.dispose()
        ImageIO.write(canvas, "png", target)
        System.err.println("Overlay geschrieben: ${target.path}")
    }
}
