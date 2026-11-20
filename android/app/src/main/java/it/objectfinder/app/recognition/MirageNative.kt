package it.objectfinder.app.recognition

/** Ponte JNI verso il motore Cactus (src/main/cpp/mirage_jni.cpp). */
internal object MirageNative {
    /** false se la libreria nativa manca o il motore non esiste per questa architettura (es. emulatore x86). */
    val isAvailable: Boolean = runCatching {
        System.loadLibrary("objectfinder_mirage")
        available()
    }.getOrDefault(false)

    @JvmStatic external fun available(): Boolean
    /** null se il modello è caricato, altrimenti il messaggio d'errore. */
    @JvmStatic external fun load(path: String): String?
    @JvmStatic external fun describe(rgb: ByteArray, width: Int, height: Int, options: String, language: String): ByteArray
    @JvmStatic external fun setThreads(threads: Int)
}
