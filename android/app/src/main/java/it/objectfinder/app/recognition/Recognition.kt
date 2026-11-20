package it.objectfinder.app.recognition

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.sqrt

/** Un oggetto candidato per la foto inquadrata. */
data class RecognitionMatch(
    val objectId: String,
    /** Somiglianza visiva migliore tra le foto dell'oggetto (coseno, 0–1). */
    val similarity: Float,
    val distanceMeters: Double?,
    /** Punteggio di classifica: somiglianza più un piccolo vantaggio per gli oggetti vicini. */
    val score: Float,
) {
    val confidence: Confidence get() = Recognition.confidence(similarity)
}

enum class Confidence(val label: String) { HIGH("molto simile"), MEDIUM("simile"), LOW("poco simile") }

/**
 * Regole del riconoscimento (pure, testabili). Le soglie sono un punto di partenza per
 * MobileNetV3 con vettori normalizzati: vanno ritarate sulle foto reali degli oggetti.
 */
object Recognition {
    /** Sotto questa somiglianza l'oggetto non viene proposto. */
    const val MIN_SIMILARITY = 0.35f
    // Prima taratura su foto reali (telefono, novembre 2026): stesso oggetto 60%, oggetto diverso 46%.
    const val HIGH_SIMILARITY = 0.60f
    const val MEDIUM_SIMILARITY = 0.50f
    /** Vantaggio massimo per un oggetto proprio qui; si dimezza ogni ~20 m. */
    const val NEARBY_BONUS = 0.12f
    const val NEARBY_SCALE_METERS = 30.0

    fun confidence(similarity: Float) = when {
        similarity >= HIGH_SIMILARITY -> Confidence.HIGH
        similarity >= MEDIUM_SIMILARITY -> Confidence.MEDIUM
        else -> Confidence.LOW
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        if (na == 0.0 || nb == 0.0) return 0f
        return (dot / (sqrt(na) * sqrt(nb))).toFloat()
    }

    /**
     * Classifica degli oggetti: per ognuno la foto più simile; la vicinanza (GPS) aiuta a scegliere
     * tra oggetti che si somigliano (es. paletti tutti uguali), senza scavalcare la somiglianza.
     */
    fun rank(
        query: FloatArray,
        photos: List<Pair<String, FloatArray>>,
        distanceOf: (String) -> Double?,
        limit: Int = 3,
    ): List<RecognitionMatch> =
        photos.groupBy({ it.first }, { it.second })
            .map { (objectId, vectors) ->
                val similarity = vectors.maxOf { cosine(query, it) }
                val distance = distanceOf(objectId)
                val bonus = distance?.let { NEARBY_BONUS * exp(-it / NEARBY_SCALE_METERS).toFloat() } ?: 0f
                RecognitionMatch(objectId, similarity, distance, similarity + bonus)
            }
            .filter { it.similarity >= MIN_SIMILARITY }
            .sortedByDescending { it.score }
            .take(limit)

    fun encode(vector: FloatArray): ByteArray =
        ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { vector.forEach { putFloat(it) } }.array()

    fun decode(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { buffer.getFloat() }
    }
}
