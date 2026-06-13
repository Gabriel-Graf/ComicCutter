package com.panela.comiccutter.onnx

import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.math.absoluteValue

/**
 * Resolves a model spec to bytes — offline-first. Three forms:
 *
 * - **local path** (`/path/best.onnx`, `~/...`): read directly, no network.
 * - **`hf:org/repo/file.onnx`** (optionally `org/repo@revision/...`): Hugging Face resolve URL.
 * - **`http(s)://...`**: direct download.
 *
 * Downloaded models are cached in `~/.cache/comic-cutter-onnx/` (key = URL hash + file name), so a
 * second run works offline. The pure URL/cache-name derivation ([toUrl], [cacheFileName]) is
 * side-effect free and therefore testable.
 */
object ModelLocator {

    /** Pure spec→URL derivation. Local path → `null` (no download needed). */
    fun toUrl(spec: String): String? = when {
        spec.startsWith("http://") || spec.startsWith("https://") -> spec
        spec.startsWith("hf:") -> huggingFaceUrl(spec.removePrefix("hf:"))
        else -> null
    }

    /** `org/repo/file.onnx` or `org/repo@rev/subfolder/file.onnx` → HF resolve URL. */
    private fun huggingFaceUrl(body: String): String {
        val parts = body.split("/").filter { it.isNotEmpty() }
        require(parts.size >= 3) {
            "hf: spec needs at least 'org/repo/file.onnx', was: 'hf:$body'"
        }
        val org = parts[0]
        val (name, revision) = parts[1].split("@").let { it[0] to (it.getOrNull(1) ?: "main") }
        val path = parts.drop(2).joinToString("/")
        return "https://huggingface.co/$org/$name/resolve/$revision/$path"
    }

    /** Stable, low-collision cache file name from URL (hash) + base name of the URL. */
    fun cacheFileName(url: String): String {
        val base = url.substringAfterLast('/').ifEmpty { "model.onnx" }
        val hash = url.hashCode().absoluteValue.toString(16)
        return "$hash-$base"
    }

    /**
     * Spec → model bytes. A local path is read directly; a remote one is downloaded (one-time)
     * into [cacheDir] and then read from there.
     */
    fun resolveBytes(
        spec: String,
        cacheDir: File = defaultCacheDir(),
    ): ByteArray {
        val url = toUrl(spec) ?: return readLocal(spec)
        val cached = File(cacheDir, cacheFileName(url))
        if (!cached.exists()) {
            cacheDir.mkdirs()
            download(url, cached)
        }
        return cached.readBytes()
    }

    private fun readLocal(spec: String): ByteArray {
        val path = if (spec.startsWith("~/")) {
            System.getProperty("user.home") + spec.substring(1)
        } else {
            spec
        }
        val file = File(path)
        require(file.isFile) { "Model file not found: $path" }
        return file.readBytes()
    }

    private fun download(url: String, target: File) {
        val client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)   // HF resolve redirects to the CDN
            .build()
        val request = HttpRequest.newBuilder(URI.create(url)).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofFile(target.toPath()))
        if (response.statusCode() != 200) {
            target.delete()
            error("Download failed (HTTP ${response.statusCode()}): $url")
        }
    }

    private fun defaultCacheDir(): File =
        File(System.getProperty("user.home"), ".cache/comic-cutter-onnx")
}
