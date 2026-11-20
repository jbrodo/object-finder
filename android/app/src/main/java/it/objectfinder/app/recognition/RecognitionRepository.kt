package it.objectfinder.app.recognition

import android.net.Uri
import it.objectfinder.app.data.Geo
import it.objectfinder.app.data.local.LOCAL_PROJECT_ID
import it.objectfinder.app.data.local.ObjectDao
import it.objectfinder.app.data.local.PhotoEmbeddingDao
import it.objectfinder.app.data.local.PhotoEmbeddingEntity
import it.objectfinder.app.data.local.PhotoEntity
import it.objectfinder.app.data.SettingsStore
import it.objectfinder.app.location.DeviceLocation
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** Esito di un riconoscimento. */
sealed interface RecognitionResult {
    data class Matches(val matches: List<RecognitionMatch>) : RecognitionResult
    /** Nessun oggetto del progetto ha foto: non c'è nulla con cui confrontare. */
    data object NothingToCompare : RecognitionResult
    data object NoMatch : RecognitionResult
    data object Failed : RecognitionResult
}

class RecognitionRepository(
    private val engine: ImageEmbedderEngine,
    private val embeddings: PhotoEmbeddingDao,
    private val objects: ObjectDao,
    private val settings: SettingsStore,
    /** Foto solo sul server: copia temporanea per calcolarne l'impronta (null se offline). */
    private val withTemporaryCopy: suspend (PhotoEntity, suspend (File) -> FloatArray?) -> FloatArray?,
) {
    private val indexing = Mutex()

    /**
     * Calcola le impronte mancanti e toglie quelle non più valide. Le foto solo sul server (non
     * ancora aperte) vengono scaricate in una copia temporanea: tutte le foto contano, non solo le
     * copertine. [progress] riceve (fatte, totali).
     */
    suspend fun indexMissing(progress: (Int, Int) -> Unit = { _, _ -> }): Int = indexing.withLock {
        embeddings.deleteStale(ImageEmbedderEngine.MODEL_ID)
        val todo = embeddings.photosWithoutEmbedding(ImageEmbedderEngine.MODEL_ID)
        var done = 0
        todo.forEachIndexed { index, photo ->
            progress(index, todo.size)
            val local = photo.localPath?.let(::File)?.takeIf { it.exists() }
            val vector = if (local != null) engine.embed(local)
            else withTemporaryCopy(photo) { file -> engine.embed(file) }
            if (vector != null) {
                embeddings.upsert(
                    PhotoEmbeddingEntity(photo.id, photo.objectId, Recognition.encode(vector), ImageEmbedderEngine.MODEL_ID)
                )
                done++
            }
        }
        progress(todo.size, todo.size)
        done
    }

    /** Confronta la foto inquadrata con quelle degli oggetti del progetto attivo. */
    suspend fun recognize(
        photo: Uri,
        here: DeviceLocation?,
        progress: (Int, Int) -> Unit = { _, _ -> },
    ): RecognitionResult {
        indexMissing(progress)
        val projectId = settings.activeProject.value?.id ?: LOCAL_PROJECT_ID
        val stored = embeddings.forProject(projectId, ImageEmbedderEngine.MODEL_ID)
        if (stored.isEmpty()) return RecognitionResult.NothingToCompare
        val query = engine.embed(photo) ?: return RecognitionResult.Failed
        val positions = objects.allByProject(projectId).associateBy { it.id }
        val matches = Recognition.rank(
            query = query,
            photos = stored.map { it.objectId to Recognition.decode(it.vector) },
            distanceOf = { id ->
                val item = positions[id] ?: return@rank null
                val lat = item.latitude ?: return@rank null
                val lon = item.longitude ?: return@rank null
                here?.let { Geo.distanceMeters(it.latitude, it.longitude, lat, lon) }
            },
        )
        return if (matches.isEmpty()) RecognitionResult.NoMatch else RecognitionResult.Matches(matches)
    }
}
