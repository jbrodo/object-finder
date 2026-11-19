package it.objectfinder.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Normalizza un'immagine importata: lato lungo max [MAX_SIDE] px, orientamento EXIF applicato
 * ai pixel, JPEG qualità [QUALITY]. I metadati EXIF (inclusa la posizione GPS) non vengono
 * copiati: la posizione dell'oggetto è già un dato esplicito e controllato dall'utente.
 */
object ImageProcessor {
    const val MAX_SIDE = 1920
    const val QUALITY = 85

    fun normalizeToJpeg(context: Context, uri: Uri): ByteArray {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // Con inJustDecodeBounds decodeStream restituisce sempre null: conta solo lo stream.
        val stream = resolver.openInputStream(uri) ?: throw IOException("Immagine non leggibile")
        stream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Formato immagine non supportato")

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_SIDE)
        }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IOException("Decodifica immagine fallita")
        val rotation = resolver.openInputStream(uri)?.use { exifRotation(ExifInterface(it)) } ?: 0
        val scaled = scaleAndRotate(decoded, MAX_SIDE, rotation)

        return ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
            out.toByteArray()
        }
    }

    /** Massima potenza di 2 che mantiene il lato lungo ≥ [maxSide] (il resto lo fa lo scaling). */
    internal fun sampleSize(width: Int, height: Int, maxSide: Int): Int {
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= maxSide) sample *= 2
        return sample
    }

    internal fun targetSize(width: Int, height: Int, maxSide: Int): Pair<Int, Int> {
        val longest = maxOf(width, height)
        if (longest <= maxSide) return width to height
        val ratio = maxSide.toFloat() / longest
        return maxOf(1, Math.round(width * ratio)) to maxOf(1, Math.round(height * ratio))
    }

    private fun exifRotation(exif: ExifInterface): Int =
        when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }

    private fun scaleAndRotate(source: Bitmap, maxSide: Int, rotation: Int): Bitmap {
        val (w, h) = targetSize(source.width, source.height, maxSide)
        if (rotation == 0 && w == source.width && h == source.height) return source
        val matrix = Matrix().apply {
            postScale(w.toFloat() / source.width, h.toFloat() / source.height)
            if (rotation != 0) postRotate(rotation.toFloat())
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }
}
