package com.panela.comiccutter.onnx

/**
 * Namentliche Registratur der verfügbaren [OnnxAdapter]. Die CLI (und jeder Konsument) wählt
 * per Name (`--adapter yolo11`); die Lib liefert `yolo11` als Default mit. Ein Community-Modell
 * mit abweichendem Output registriert seinen eigenen Adapter zur Laufzeit über [register] —
 * danach ist er per Name auflösbar, ohne Änderung an Runner oder CLI.
 *
 * Jeder Eintrag ist eine Factory `(inputSize) -> OnnxAdapter`, damit dieselbe Adapter-Familie
 * mit unterschiedlicher Input-Größe instanziierbar bleibt.
 */
object AdapterRegistry {

    /** Default-Input-Größe, wenn die CLI keine `--imgsz` übergibt. */
    const val DEFAULT_INPUT_SIZE: Int = 1024

    private val factories: MutableMap<String, (Int) -> OnnxAdapter> = linkedMapOf(
        "yolo11" to { size -> Yolo11Adapter(size) },
    )

    /** Registriert (oder ersetzt) einen Adapter unter [name] (case-insensitiv). */
    fun register(name: String, factory: (Int) -> OnnxAdapter) {
        factories[name.lowercase()] = factory
    }

    /** Alle registrierten Adapter-Namen, in Einfüge-Reihenfolge. */
    fun names(): List<String> = factories.keys.toList()

    /**
     * Erzeugt den Adapter [name] mit [inputSize]. Unbekannter Name → [IllegalArgumentException]
     * mit Auflistung der bekannten Namen.
     */
    fun create(name: String, inputSize: Int = DEFAULT_INPUT_SIZE): OnnxAdapter {
        val factory = factories[name.lowercase()]
            ?: throw IllegalArgumentException("Unbekannter Adapter '$name'. Bekannt: ${names().joinToString(", ")}")
        return factory(inputSize)
    }
}
