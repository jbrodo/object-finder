package it.objectfinder.app.recognition

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imageembedder.ImageEmbedder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * MediaPipe Image Embedder (MobileNetV3 large, modello negli asset): trasforma una foto in un
 * vettore; foto dello stesso oggetto danno vettori vicini. Tutto sul telefono, niente rete.
 */
class ImageEmbedderEngine(private val context: Context) {
    private val lock = Mutex()
    private var embedder: ImageEmbedder? = null

    private fun instance(): ImageEmbedder = embedder ?: ImageEmbedder.createFromOptions(
        context,
        ImageEmbedder.ImageEmbedderOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build())
            .setRunningMode(RunningMode.IMAGE)
            .setL2Normalize(true)
            .setQuantize(false)
            .build(),
    ).also { embedder = it }

    suspend fun embed(file: File): FloatArray? = withContext(Dispatchers.Default) {
        val bitmap = decodeSampled { BitmapFactory.decodeFile(file.absolutePath, it) } ?: return@withContext null
        embed(bitmap)
    }

    suspend fun embed(uri: Uri): FloatArray? = withContext(Dispatchers.Default) {
        val bitmap = decodeSampled { opts ->
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } ?: return@withContext null
        embed(bitmap)
    }

    private suspend fun embed(bitmap: Bitmap): FloatArray? = lock.withLock {
        runCatching {
            val result = instance().embed(BitmapImageBuilder(bitmap).build())
            result.embeddingResult().embeddings().firstOrNull()?.floatEmbedding()
        }.getOrNull().also { bitmap.recycle() }
    }

    /** Il modello lavora a 224 px: inutile decodificare la foto intera. */
    private fun decodeSampled(decode: (BitmapFactory.Options) -> Bitmap?): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decode(bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 448) sample *= 2
        return decode(BitmapFactory.Options().apply { inSampleSize = sample })
    }

    fun close() {
        embedder?.close()
        embedder = null
    }

    companion object {
        const val MODEL_ASSET = "image_embedder.tflite"
        /** Cambia quando cambia il modello: le impronte vecchie vengono ricalcolate. */
        const val MODEL_ID = "mobilenet_v3_large_f32_v1"
    }
}
