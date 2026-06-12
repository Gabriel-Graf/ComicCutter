package com.panela.comiccutter.onnx.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CliArgsTest {

    @Test
    fun minimal_setzt_pflichtfelder_und_defaults() {
        val args = CliArgs.parse(arrayOf("--model", "best.onnx", "--image", "seite.jpg"))
        assertEquals("best.onnx", args.model)
        assertEquals("seite.jpg", args.image)
        assertEquals("yolo11", args.adapter)
        assertEquals(1024, args.inputSize)
        assertEquals(0.25f, args.minScore)
        assertEquals(0.7f, args.nmsIoU)
        assertFalse(args.rtl)
        assertNull(args.overlay)
    }

    @Test
    fun liest_alle_optionalen_argumente() {
        val args = CliArgs.parse(
            arrayOf(
                "--model", "hf:org/repo/best.onnx", "--image", "s.jpg",
                "--adapter", "yolo11", "--imgsz", "640", "--conf", "0.4",
                "--nms", "0.5", "--rtl", "--overlay", "out.png",
            ),
        )
        assertEquals("hf:org/repo/best.onnx", args.model)
        assertEquals(640, args.inputSize)
        assertEquals(0.4f, args.minScore)
        assertEquals(0.5f, args.nmsIoU)
        assertTrue(args.rtl)
        assertEquals("out.png", args.overlay)
    }

    @Test
    fun fehlendes_model_wirft() {
        assertFailsWith<IllegalArgumentException> {
            CliArgs.parse(arrayOf("--image", "seite.jpg"))
        }
    }

    @Test
    fun fehlendes_image_wirft() {
        assertFailsWith<IllegalArgumentException> {
            CliArgs.parse(arrayOf("--model", "best.onnx"))
        }
    }

    @Test
    fun unbekanntes_argument_wirft() {
        assertFailsWith<IllegalArgumentException> {
            CliArgs.parse(arrayOf("--model", "m", "--image", "i", "--foo"))
        }
    }

    @Test
    fun kaputte_zahl_wirft() {
        assertFailsWith<IllegalArgumentException> {
            CliArgs.parse(arrayOf("--model", "m", "--image", "i", "--conf", "abc"))
        }
    }

    @Test
    fun wertloses_flag_am_ende_wirft() {
        assertFailsWith<IllegalArgumentException> {
            CliArgs.parse(arrayOf("--model", "m", "--image"))
        }
    }
}
