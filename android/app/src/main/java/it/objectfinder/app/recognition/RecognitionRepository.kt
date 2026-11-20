package it.objectfinder.app.recognition

import android.net.Uri
import it.objectfinder.app.data.Geo
import it.objectfinder.app.data.local.LOCAL_PROJECT_ID
import it.objectfinder.app.data.local.ObjectDao
import it.objectfinder.app.data.local.PhotoEmbeddingDao
import it.objectfinder.app.data.local.PhotoEmbeddingEntity
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
) {
    private val indexing = Mutex()

    /** Calcola le impronte mancanti (foto nuove o scaricate) e toglie quelle non più valide. */
    suspend fun indexMissing(): Int = indexing.withLock {
        embeddings.deleteStale(ImageEmbedderEngine.MODEL_ID)
        var done = 0
        embeddings.photosWithoutEmbedding(ImageEmbedderEngine.MODEL_ID).forEach { photo ->
            val file = photo.localPath?.let(::File)?.takeIf { it.exists() } ?: return@forEach
            val vector = engine.embed(file) ?: return@forEach
            embeddings.upsert(
                PhotoEmbeddingEntity(photo.id, photo.objectId, Recognition.encode(vector), ImageEmbedderEngine.MODEL_ID)
            )
            done++
        }
        done
    }

    /** Confronta la foto inquadrata con quelle degli oggetti del progetto attivo. */
    suspend fun recognize(photo: Uri, here: DeviceLocation?): RecognitionResult {
        indexMissing()
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
