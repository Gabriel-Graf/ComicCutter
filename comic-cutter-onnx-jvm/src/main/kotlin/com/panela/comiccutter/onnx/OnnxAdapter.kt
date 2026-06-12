package com.panela.comiccutter.onnx

import ai.onnxruntime.OrtSession
import com.panela.comiccutter.RawDetection
import com.panela.comiccutter.model.RenderedPage

/**
 * Modell-spezifische Naht für [OnnxModelRunner]: kapselt Input-Geometrie, Preprocessing und
 * Output-Decode eines konkreten ONNX-Modells. Der Runner kümmert sich nur ums Session-Plumbing
 * (Laden, `session.run`, Tensor-Lebenszyklus) und delegiert alles Modell-Spezifische hierher.
 *
 * So lässt sich ein abweichendes Community-Modell anbinden — andere Input-Größe, NHWC statt
 * NCHW, BGR statt RGB, anderes Output-Layout (xyxy, transponiert, mit Objectness) — durch eine
 * neue Implementierung, ohne Änderung am Runner und ohne Python zur Laufzeit. Registrierung
 * über die [AdapterRegistry].
 */
interface OnnxAdapter {
    /** Kantenlänge des quadratischen Letterbox-Inputs (z.B. 1024). */
    val inputSize: Int

    /** Form des Input-Tensors, z.B. `(1,3,size,size)` für NCHW-RGB. */
    val inputShape: LongArray

    /** Seite → flacher Float-Tensor passend zu [inputShape] (Letterbox-skaliert via [lb]). */
    fun preprocess(page: RenderedPage, lb: Letterbox): FloatArray

    /** Roh-Output der Session → Detektionen in Seiten-Pixel-Koordinaten, vor jeder Filterung. */
    fun decode(result: OrtSession.Result, lb: Letterbox): List<RawDetection>
}
