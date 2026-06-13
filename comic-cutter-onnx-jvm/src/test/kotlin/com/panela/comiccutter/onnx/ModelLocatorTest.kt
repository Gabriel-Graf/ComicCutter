package com.panela.comiccutter.onnx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelLocatorTest {

    @Test
    fun local_path_returns_no_url() {
        assertNull(ModelLocator.toUrl("/home/user/models/best.onnx"), "absolute path")
        assertNull(ModelLocator.toUrl("models/best.onnx"), "relative path")
        assertNull(ModelLocator.toUrl("~/best.onnx"), "home path")
    }

    @Test
    fun hf_spec_becomes_resolve_url() {
        assertEquals(
            "https://huggingface.co/org/repo/resolve/main/best.onnx",
            ModelLocator.toUrl("hf:org/repo/best.onnx"),
        )
    }

    @Test
    fun hf_spec_with_revision_and_subfolder() {
        assertEquals(
            "https://huggingface.co/org/repo/resolve/v2/weights/best.onnx",
            ModelLocator.toUrl("hf:org/repo@v2/weights/best.onnx"),
        )
    }

    @Test
    fun http_url_stays_unchanged() {
        val url = "https://example.com/models/best.onnx"
        assertEquals(url, ModelLocator.toUrl(url))
    }

    @Test
    fun cache_name_is_stable_and_contains_base_name() {
        val url = "https://huggingface.co/org/repo/resolve/main/best.onnx"
        val a = ModelLocator.cacheFileName(url)
        val b = ModelLocator.cacheFileName(url)
        assertEquals(a, b)
        assertTrue(a.endsWith("best.onnx"), "cache name should contain the base name: $a")
    }
}
