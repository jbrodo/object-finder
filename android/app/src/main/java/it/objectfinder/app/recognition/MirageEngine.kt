package it.objectfinder.app.recognition

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/**
 * Mirage (Cactus, Apache-2.0) sul telefono: nome possibile dell'oggetto e testo letto nella foto.
 * Il modello (22 MB) non è nell'APK: si scarica una volta, su richiesta, con revisione e SHA-256
 * fissati. Disponibile solo su ARM (il motore non esiste per x86).
 */
class MirageEngine(context: Context) {
    private val appContext = context.applicationContext
    private val lock = Mutex()
    private var loaded = false

    val isSupported: Boolean get() = MirageNative.isAvailable
    private val modelFile get() = File(File(appContext.filesDir, "models"), MODEL_FILE)
    val isModelReady: Boolean get() = modelFile.exists() && modelFile.length() == MODEL_SIZE

    /** Scarica il modello (verifica SHA-256 prima di renderlo usabile). [progress] tra 0 e 1. */
    suspend fun downloadModel(progress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        if (isModelReady) return@withContext
        val target = modelFile.apply { parentFile?.mkdirs() }
        val partial = File(target.parentFile, "$MODEL_FILE.part")
        val connection = URI(MODEL_URL).toURL().openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        try {
            check(connection.responseCode == 200) { "download non riuscito (HTTP ${connection.responseCode})" }
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        done += n
                        progress((done.toFloat() / MODEL_SIZE).coerceAtMost(1f))
                    }
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            check(sha == MODEL_SHA256) { "modello scaricato non valido" }
            check(partial.renameTo(target)) { "salvataggio del modello non riuscito" }
        } finally {
            connection.disconnect()
            partial.delete()
        }
    }

    /** Analizza una foto già sul telefono. Lancia eccezioni con messaggi da mostrare. */
    suspend fun describe(photo: File, language: String = "it"): MirageSuggestions = withContext(Dispatchers.Default) {
        check(isSupported) { "Suggerimenti non disponibili su questo dispositivo" }
        check(isModelReady) { "Modello non scaricato" }
        lock.withLock {
            if (!loaded) {
                MirageNative.load(modelFile.absolutePath)?.let { error(it) }
                loaded = true
            }
            val bitmap = decode(photo) ?: error("Foto non leggibile")
            val rgb = toRgb(bitmap)
            val raw = MirageNative.describe(rgb, bitmap.width, bitmap.height, OPTIONS, language)
            bitmap.recycle()
            MirageParse.parse(String(raw, Charsets.UTF_8))
        }
    }

    /** Lato lungo ~768 px: il modello lavora a 512, inutile passare la foto intera. */
    private fun decode(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 768) sample *= 2
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 },
        )
    }

    private fun toRgb(bitmap: Bitmap): ByteArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val rgb = ByteArray(pixels.size * 3)
        pixels.forEachIndexed { i, p ->
            rgb[i * 3] = (p shr 16 and 0xFF).toByte()
            rgb[i * 3 + 1] = (p shr 8 and 0xFF).toByte()
            rgb[i * 3 + 2] = (p and 0xFF).toByte()
        }
        return rgb
    }

    companion object {
        private const val REVISION = "76c1e9e53381aca8fc3843c636216c96ff220122"
        const val MODEL_URL = "https://huggingface.co/Cactus-Compute/mirage/resolve/$REVISION/mirage.cact"
        const val MODEL_SHA256 = "4249a0f7515272ff76b9414bec17d7e7daf84b15b66c7fb4f17fce1e66f9f237"
        const val MODEL_SIZE = 21_611_043L
        private const val MODEL_FILE = "mirage-76c1e9e.cact"
        /** Solo ciò che serve: niente maschere (pesanti) né relazioni. */
        private const val OPTIONS = """{"sections":["objects","text"]}"""
    }
}
