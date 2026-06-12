package com.panela.comiccutter.onnx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelLocatorTest {

    @Test
    fun lokaler_pfad_liefert_keine_url() {
        assertNull(ModelLocator.toUrl("/home/user/models/best.onnx"), "absoluter Pfad")
        assertNull(ModelLocator.toUrl("models/best.onnx"), "relativer Pfad")
        assertNull(ModelLocator.toUrl("~/best.onnx"), "Home-Pfad")
    }

    @Test
    fun hf_spec_wird_zur_resolve_url() {
        assertEquals(
            "https://huggingface.co/org/repo/resolve/main/best.onnx",
            ModelLocator.toUrl("hf:org/repo/best.onnx"),
        )
    }

    @Test
    fun hf_spec_mit_revision_und_unterordner() {
        assertEquals(
            "https://huggingface.co/org/repo/resolve/v2/weights/best.onnx",
            ModelLocator.toUrl("hf:org/repo@v2/weights/best.onnx"),
        )
    }

    @Test
    fun http_url_bleibt_unveraendert() {
        val url = "https://example.com/models/best.onnx"
        assertEquals(url, ModelLocator.toUrl(url))
    }

    @Test
    fun cache_name_ist_stabil_und_enthaelt_basisnamen() {
        val url = "https://huggingface.co/org/repo/resolve/main/best.onnx"
        val a = ModelLocator.cacheFileName(url)
        val b = ModelLocator.cacheFileName(url)
        assertEquals(a, b)
        assertTrue(a.endsWith("best.onnx"), "Cache-Name sollte den Basisnamen enthalten: $a")
    }
}
