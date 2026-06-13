package com.panela.comiccutter.onnx

/**
 * Name-based registry of the available [OnnxAdapter]s. The CLI (and every consumer) selects one
 * by name (`--adapter yolo11`); the library ships `yolo11` as the default. A community model with
 * a different output registers its own adapter at runtime via [register] — after that it resolves
 * by name, without any change to the runner or the CLI.
 *
 * Each entry is a factory `(inputSize) -> OnnxAdapter`, so the same adapter family can be
 * instantiated with a different input size.
 */
object AdapterRegistry {

    /** Default input size when the CLI does not pass `--imgsz`. */
    const val DEFAULT_INPUT_SIZE: Int = 1024

    private val factories: MutableMap<String, (Int) -> OnnxAdapter> = linkedMapOf(
        "yolo11" to { size -> Yolo11Adapter(size) },
    )

    /** Registers (or replaces) an adapter under [name] (case-insensitive). */
    fun register(name: String, factory: (Int) -> OnnxAdapter) {
        factories[name.lowercase()] = factory
    }

    /** All registered adapter names, in insertion order. */
    fun names(): List<String> = factories.keys.toList()

    /**
     * Creates the adapter [name] with [inputSize]. Unknown name → [IllegalArgumentException]
     * listing the known names.
     */
    fun create(name: String, inputSize: Int = DEFAULT_INPUT_SIZE): OnnxAdapter {
        val factory = factories[name.lowercase()]
            ?: throw IllegalArgumentException("Unknown adapter '$name'. Known: ${names().joinToString(", ")}")
        return factory(inputSize)
    }
}
