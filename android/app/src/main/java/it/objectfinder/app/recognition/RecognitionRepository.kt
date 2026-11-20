package it.objectfinder.app.recognition

import it.objectfinder.app.data.remote.SupabaseException
import it.objectfinder.app.data.local.PhotoSyncStatus
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

/** Impronte condivise sul server; implementata sulla REST di Supabase, finta nei test. */
interface SharedEmbeddings {
    /** photoId → vettore (float32 LE) per le foto indicate che hanno già un'impronta sul server. */
    suspend fun fetch(photoIds: List<String>, model: String): Map<String, ByteArray>
    /** Invia; un errore 4xx su una riga (foto cancellata, permessi) la rende "non inviabile". */
    suspend fun push(model: String, rows: Map<String, ByteArray>)
}

/** Chi può fare cosa con le impronte sul server: null = offline/archivio locale. */
data class EmbeddingAccess(val projectId: String, val canWrite: Boolean)

class RecognitionRepository(
    private val engine: ImageEmbedderEngine,
    private val embeddings: PhotoEmbeddingDao,
    private val objects: ObjectDao,
    private val settings: SettingsStore,
    /** Foto solo sul server: copia temporanea per calcolarne l'impronta (null se offline). */
    private val withTemporaryCopy: suspend (PhotoEntity, suspend (File) -> FloatArray?) -> FloatArray?,
    private val shared: SharedEmbeddings? = null,
    private val access: () -> EmbeddingAccess? = { null },
) {
    private val indexing = Mutex()

    /**
     * Calcola le impronte mancanti e toglie quelle non più valide. Le foto solo sul server (non
     * ancora aperte) vengono scaricate in una copia temporanea: tutte le foto contano, non solo le
     * copertine. [progress] riceve (fatte, totali).
     */
    suspend fun indexMissing(progress: (Int, Int) -> Unit = { _, _ -> }): Int = indexing.withLock {
        val model = ImageEmbedderEngine.MODEL_ID
        embeddings.deleteStale(model)
        val who = access()
        var todo = embeddings.photosWithoutEmbedding(model)
        // 1. Prima le impronte già sul server: niente download delle foto né calcolo.
        val remote = shared
        if (who != null && remote != null) {
            val onServer = todo.filter { it.syncStatus == PhotoSyncStatus.SYNCED.wire }
            val found = HashMap<String, ByteArray>()
            for (chunk in onServer.chunked(FETCH_CHUNK)) {
                val got = runCatching { remote.fetch(chunk.map { it.id }, model) }.getOrNull() ?: break
                found.putAll(got.filterValues { it.size >= 4 && it.size % 4 == 0 })
            }
            onServer.forEach { photo ->
                found[photo.id]?.let { embeddings.upsert(PhotoEmbeddingEntity(photo.id, photo.objectId, it, model, uploaded = true)) }
            }
            todo = todo.filter { it.id !in found }
        }
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
        // 3. Le impronte nuove vanno sul server (solo chi può aggiungere foto).
        if (who != null && who.canWrite && shared != null) pushPending(who.projectId)
        done
    }

    private suspend fun pushPending(projectId: String) {
        val model = ImageEmbedderEngine.MODEL_ID
        for (chunk in embeddings.notUploaded(projectId, model).chunked(PUSH_CHUNK)) {
            when (send(model, chunk)) {
                Sent.OK, Sent.REJECTED -> embeddings.markUploaded(chunk.map { it.photoId }, model)
                // Una riga rifiutata (es. foto cancellata sul server nel frattempo) non blocca le altre.
                Sent.REJECTED_BATCH -> chunk.forEach { one ->
                    if (send(model, listOf(one)) == Sent.OFFLINE) return
                    embeddings.markUploaded(listOf(one.photoId), model)
                }
                Sent.OFFLINE -> return // si riprova la prossima volta
            }
        }
    }

    private enum class Sent { OK, REJECTED, REJECTED_BATCH, OFFLINE }

    private suspend fun send(model: String, rows: List<PhotoEmbeddingEntity>): Sent {
        val error = runCatching { shared!!.push(model, rows.associate { it.photoId to it.vector }) }.exceptionOrNull()
            ?: return Sent.OK
        // 4xx: ripetere non serve (permessi, foto sparita); il resto è rete/server: si riprova.
        val permanent = error is SupabaseException.Http && error.status in 400..499
        return when {
            !permanent -> Sent.OFFLINE
            rows.size > 1 -> Sent.REJECTED_BATCH
            else -> Sent.REJECTED
        }
    }

    private companion object {
        const val FETCH_CHUNK = 100
        const val PUSH_CHUNK = 200
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
