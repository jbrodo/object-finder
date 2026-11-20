package it.objectfinder.app.recognition

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
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
import java.nio.FloatBuffer
import java.security.MessageDigest

/**
 * Riconoscimento delle piante sul telefono: modello pubblico PlantNet/mobilenetv4-conv-s-plantclef2024
 * (Apache-2.0, 7.806 specie della flora europea sud-occidentale), eseguito con ONNX Runtime.
 * Il modello (50 MB) si scarica una volta su richiesta, con revisione e SHA-256 fissati; le etichette
 * sono nell'APK (asset generato dalla build). Nessuna chiamata a Pl@ntNet.
 */
class PlantEngine(context: Context) : OfflinePlants {
    private val appContext = context.applicationContext
    private val modelFile get() = File(File(appContext.filesDir, "models"), MODEL_FILE)
    private val lock = Mutex()
    private var session: OrtSession? = null
    private val labels: List<PlantLabel> by lazy {
        PlantModel.parseLabels(appContext.assets.open("plant_labels.tsv").bufferedReader().use { it.readText() })
    }

    /** false se ONNX Runtime non c'è per questa architettura (telefoni a 32 bit). */
    override val isSupported: Boolean by lazy {
        runCatching { OrtEnvironment.getEnvironment() }.isSuccess
    }

    override val isModelReady: Boolean get() = modelFile.exists() && modelFile.length() == MODEL_SIZE

    /** Scarica il modello e lo rende usabile solo se lo SHA-256 corrisponde. [progress] tra 0 e 1. */
    suspend fun downloadModel(progress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val target = modelFile
        target.parentFile?.mkdirs()
        val partial = File(target.parentFile, "$MODEL_FILE.part")
        val connection = URI(MODEL_URL).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            check(connection.responseCode == 200) { "download non riuscito (HTTP ${connection.responseCode})" }
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            connection.inputStream.use { input ->
                partial.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        done += n
                        progress((done.toFloat() / MODEL_SIZE).coerceAtMost(1f))
                    }
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            check(sha == MODEL_SHA256 && done == MODEL_SIZE) { "modello scaricato non valido" }
            check(partial.renameTo(target)) { "impossibile salvare il modello" }
        } finally {
            connection.disconnect()
            partial.delete()
        }
    }

    /** Classifica la foto (già orientata, come quelle salvate dall'app). */
    override suspend fun identify(photo: File): PlantIdentification? = withContext(Dispatchers.Default) {
        if (!isModelReady) return@withContext null
        val bitmap = decode(photo) ?: return@withContext null
        val scaled = Bitmap.createScaledBitmap(bitmap, PlantModel.INPUT_SIZE, PlantModel.INPUT_SIZE, true)
        val pixels = IntArray(PlantModel.INPUT_SIZE * PlantModel.INPUT_SIZE)
        scaled.getPixels(pixels, 0, PlantModel.INPUT_SIZE, 0, 0, PlantModel.INPUT_SIZE, PlantModel.INPUT_SIZE)
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        val input = PlantModel.toInput(pixels)
        val logits = lock.withLock {
            val env = OrtEnvironment.getEnvironment()
            val s = session ?: env.createSession(
                modelFile.absolutePath,
                OrtSession.SessionOptions().apply { setIntraOpNumThreads(THREADS) },
            ).also { session = it }
            OnnxTensor.createTensor(
                env, FloatBuffer.wrap(input), longArrayOf(1, 3, PlantModel.INPUT_SIZE.toLong(), PlantModel.INPUT_SIZE.toLong()),
            ).use { tensor ->
                s.run(mapOf(INPUT_NAME to tensor)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    (result[0].value as Array<FloatArray>)[0]
                }
            }
        }
        PlantModel.rank(logits, labels)
    }

    /** Lato corto almeno ~512 px: il modello lavora a 512, inutile decodificare la foto intera. */
    private fun decode(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PlantModel.INPUT_SIZE) sample *= 2
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 },
        )
    }

    /** JPEG per Pl@ntNet: lato corto fino a 1280 px (quello che usano), qualità 90. */
    suspend fun jpegForUpload(photo: File): ByteArray = withContext(Dispatchers.Default) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(photo.absolutePath, bounds)
        check(bounds.outWidth > 0) { "foto non leggibile" }
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= UPLOAD_SHORT_SIDE) sample *= 2
        val decoded = BitmapFactory.decodeFile(photo.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("foto non leggibile")
        val short = minOf(decoded.width, decoded.height)
        val bitmap = if (short > UPLOAD_SHORT_SIDE) {
            val k = UPLOAD_SHORT_SIDE.toFloat() / short
            Bitmap.createScaledBitmap(decoded, (decoded.width * k).toInt(), (decoded.height * k).toInt(), true)
        } else decoded
        java.io.ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            if (bitmap !== decoded) bitmap.recycle()
            decoded.recycle()
            out.toByteArray()
        }
    }

    fun close() {
        session?.close()
        session = null
    }

    companion object {
        private const val REVISION = "119d3f3bf1ea7029e7248288b9ad5314fa02a0bc"
        const val MODEL_URL = "https://huggingface.co/PlantNet/mobilenetv4-conv-s-plantclef2024/resolve/$REVISION/onnx/model.onnx"
        const val MODEL_SHA256 = "46cc7ef8d1321a2e1458bfc7e2fa07d808149a7ffc515f3ef09fbbac1d02ceeb"
        const val MODEL_SIZE = 49_942_990L
        private const val MODEL_FILE = "plantclef2024-mnv4s-119d3f3.onnx"
        private const val INPUT_NAME = "pixel_values"
        private const val THREADS = 4
        private const val UPLOAD_SHORT_SIDE = 1280
    }
}
