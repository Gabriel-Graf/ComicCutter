package com.panela.comiccutter.onnx

import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.math.absoluteValue

/**
 * Löst eine Modell-Spec zu Bytes auf — offline-first. Drei Formen:
 *
 * - **lokaler Pfad** (`/pfad/best.onnx`, `~/...`): direkt gelesen, kein Netz.
 * - **`hf:org/repo/datei.onnx`** (optional `org/repo@revision/...`): Hugging-Face-Resolve-URL.
 * - **`http(s)://...`**: direkter Download.
 *
 * Heruntergeladene Modelle werden in `~/.cache/comic-cutter-onnx/` gecacht (Key = URL-Hash +
 * Dateiname), sodass ein zweiter Lauf offline funktioniert. Die reine URL-/Cache-Namens-Ableitung
 * ([toUrl], [cacheFileName]) ist seiteneffektfrei und damit testbar.
 */
object ModelLocator {

    /** Reine Spec→URL-Ableitung. Lokaler Pfad → `null` (kein Download nötig). */
    fun toUrl(spec: String): String? = when {
        spec.startsWith("http://") || spec.startsWith("https://") -> spec
        spec.startsWith("hf:") -> huggingFaceUrl(spec.removePrefix("hf:"))
        else -> null
    }

    /** `org/repo/datei.onnx` oder `org/repo@rev/unterordner/datei.onnx` → HF-Resolve-URL. */
    private fun huggingFaceUrl(body: String): String {
        val parts = body.split("/").filter { it.isNotEmpty() }
        require(parts.size >= 3) {
            "hf:-Spec braucht mindestens 'org/repo/datei.onnx', war: 'hf:$body'"
        }
        val org = parts[0]
        val (name, revision) = parts[1].split("@").let { it[0] to (it.getOrNull(1) ?: "main") }
        val path = parts.drop(2).joinToString("/")
        return "https://huggingface.co/$org/$name/resolve/$revision/$path"
    }

    /** Stabiler, kollisionsarmer Cache-Dateiname aus URL (Hash) + Basisname der URL. */
    fun cacheFileName(url: String): String {
        val base = url.substringAfterLast('/').ifEmpty { "model.onnx" }
        val hash = url.hashCode().absoluteValue.toString(16)
        return "$hash-$base"
    }

    /**
     * Spec → Modell-Bytes. Lokaler Pfad wird direkt gelesen; remote wird (einmalig) in
     * [cacheDir] geladen und danach von dort gelesen.
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
        require(file.isFile) { "Modelldatei nicht gefunden: $path" }
        return file.readBytes()
    }

    private fun download(url: String, target: File) {
        val client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)   // HF-Resolve leitet auf die CDN um
            .build()
        val request = HttpRequest.newBuilder(URI.create(url)).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofFile(target.toPath()))
        if (response.statusCode() != 200) {
            target.delete()
            error("Download fehlgeschlagen (HTTP ${response.statusCode()}): $url")
        }
    }

    private fun defaultCacheDir(): File =
        File(System.getProperty("user.home"), ".cache/comic-cutter-onnx")
}
