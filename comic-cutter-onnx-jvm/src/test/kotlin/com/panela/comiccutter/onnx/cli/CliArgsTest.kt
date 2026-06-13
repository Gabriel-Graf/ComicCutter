package com.panela.comiccutter.onnx.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CliArgsTest {

    @Test
    fun minimal_sets_required_fields_and_defaults() {
        val args = CliArgs.parse(arrayOf("--model", "best.onnx", "--image", "page.jpg"))
        assertEquals("best.onnx", args.model)
        assertEquals("page.jpg", args.image)
        assertEquals("yolo11", args.adapter)
        assertEquals(1024, args.inputSize)
        assertEquals(0.25f, args.minScore)
        assertEquals(0.7f, args.nmsIoU)
        assertFalse(args.rtl)
        assertNull(args.overlay)
    }

    @Test
    fun reads_all_optional_arguments() {
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
    fun missing_model_throws() {
        assertFailsWith<IllegalArgumentException> {
            CliArgs.parse(arrayOf("--image", "page.jpg"))
        }
    }

    @Test
    fun missing_image_throws() {
        assertFailsWith<IllegalArgumentException> {
            CliArgs.parse(arrayOf("--model", "best.onnx"))
        }
    }

    @Test
    fun unknown_argument_throws() {
        assertFailsWith<IllegalArgumentException> {
            CliArgs.parse(arrayOf("--model", "m", "--image", "i", "--foo"))
        }
    }

    @Test
    fun malformed_number_throws() {
        assertFailsWith<IllegalArgumentException> {
            CliArgs.parse(arrayOf("--model", "m", "--image", "i", "--conf", "abc"))
        }
    }

    @Test
    fun valueless_flag_at_end_throws() {
        assertFailsWith<IllegalArgumentException> {
            CliArgs.parse(arrayOf("--model", "m", "--image"))
        }
    }
}
