package com.panela.comiccutter.onnx

import kotlin.test.Test
import kotlin.test.assertEquals

class LetterboxTest {
    @Test
    fun back_projection_reverses_scaling_and_padding() {
        // 200x100 page → 1024 square: r = 1024/200 = 5.12, new height 512, padY = 256, padX = 0
        val lb = Letterbox.of(srcW = 200, srcH = 100, target = 1024)
        assertEquals(0, lb.padX)
        assertEquals(256, lb.padY)
        // Box center in model space (512, 512), 20x10 scaled → page box around center (100, 50)
        val box = lb.boxFromCenter(cx = 512f, cy = 512f, w = 5.12f * 20, h = 5.12f * 10)
        assertEquals(90, box[0])   // x1 = 100 - 10
        assertEquals(45, box[1])   // y1 = 50 - 5
        assertEquals(20, box[2])   // width
        assertEquals(10, box[3])   // height
    }

    @Test
    fun forward_indices_hit_source_pixels() {
        val lb = Letterbox.of(srcW = 200, srcH = 100, target = 1024)
        assertEquals(100, lb.sourceX(512))   // model-space center → page center x
        assertEquals(50, lb.sourceY(512))     // (512 - 256) / 5.12 = 50
    }
}
