package it.objectfinder.app.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import it.objectfinder.app.data.local.PhotoDao
import it.objectfinder.app.data.local.PhotoEntity
import it.objectfinder.app.data.local.PhotoSyncStatus
import it.objectfinder.app.data.remote.PhotoRowDto
import it.objectfinder.app.data.remote.SupabaseException
import it.objectfinder.app.data.remote.SupabaseRestApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class PhotoSyncReport(val uploaded: Int, val deleted: Int, val downloaded: Int)

/**
 * Foto degli oggetti, offline-first: file JPEG nella memoria privata dell'app + riga Room.
 * Sync: upload su Storage → riga `object_photos`; cancellazioni propagate; download delle foto
 * aggiunte da altri membri. Il path remoto è `<project>/<object>/<photo>.jpg` (policy 005).
 */
class PhotoRepository(
    context: Context,
    private val dao: PhotoDao,
    private val api: SupabaseRestApi,
) {
    private val appContext = context.applicationContext
    private val photosDir = File(appContext.filesDir, "photos")
    private val cameraDir = File(appContext.cacheDir, "camera")

    fun observeForObject(objectId: String): Flow<List<PhotoEntity>> = dao.observeForObject(objectId)

    fun observePendingCount(): Flow<Int> = dao.observePendingCount()

    /** objectId → path locale della prima foto (copertina in elenco). */
    fun observeCovers(): Flow<Map<String, String>> = dao.observeAllVisible().map { photos ->
        photos.groupBy { it.objectId }.mapValues { (_, list) -> list.first().localPath!! }
    }

    /** URI temporaneo (FileProvider) da passare all'app fotocamera. */
    fun newCameraUri(): Uri {
        cameraDir.mkdirs()
        val file = File(cameraDir, "capture-${UUID.randomUUID()}.jpg")
        return FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file)
    }

    /** Importa una foto (fotocamera o galleria) normalizzandola; resta in coda di upload. */
    suspend fun addPhoto(objectId: String, source: Uri): PhotoEntity = withContext(Dispatchers.IO) {
        try {
            val jpeg = ImageProcessor.normalizeToJpeg(appContext, source)
            val id = UUID.randomUUID().toString()
            val file = File(objectDir(objectId), "$id.jpg").apply { writeBytes(jpeg) }
            PhotoEntity(id = id, objectId = objectId, localPath = file.absolutePath).also { dao.upsert(it) }
        } finally {
            cleanupCameraTemp(source)
        }
    }

    /** Scatti temporanei rimasti da acquisizioni annullate o fallite (più vecchi di 1 ora). */
    suspend fun purgeStaleCameraFiles() = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - 60 * 60 * 1000
        cameraDir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }

    /** Elimina una foto: subito se mai caricata, altrimenti tombstone sincronizzato. */
    suspend fun deletePhoto(photoId: String) = withContext(Dispatchers.IO) {
        val photo = dao.getById(photoId) ?: return@withContext
        if (photo.storagePath == null) {
            photo.localPath?.let { File(it).delete() }
            dao.delete(photo.id)
        } else {
            dao.upsert(photo.copy(syncStatus = PhotoSyncStatus.PENDING_DELETE.wire))
        }
    }

    /** Rimuove le foto di un oggetto mai salvato (editor annullato). */
    suspend fun discardAllForObject(objectId: String) = withContext(Dispatchers.IO) {
        dao.allForObject(objectId).forEach { photo ->
            photo.localPath?.let { File(it).delete() }
            dao.delete(photo.id)
        }
        objectDir(objectId).delete()
    }

    /**
     * Da chiamare dopo il push degli oggetti (la policy Storage richiede che l'oggetto esista).
     * [canWrite] false (viewer): solo download/riconciliazione.
     */
    suspend fun sync(projectId: String, canWrite: Boolean): PhotoSyncReport = withContext(Dispatchers.IO) {
        var uploaded = 0
        var deleted = 0
        if (canWrite) {
            dao.byProjectAndStatus(projectId, PhotoSyncStatus.PENDING_DELETE.wire).forEach { photo ->
                photo.storagePath?.let { path ->
                    api.deletePhotoRow(photo.id)
                    runCatching { api.deletePhotoFile(path) } // file orfano: tollerato, la riga è la fonte di verità
                }
                photo.localPath?.let { File(it).delete() }
                dao.delete(photo.id)
                deleted++
            }
            dao.byProjectAndStatus(projectId, PhotoSyncStatus.PENDING_UPLOAD.wire).forEach { photo ->
                val local = photo.localPath?.let(::File)?.takeIf { it.exists() }
                if (local == null) {
                    dao.delete(photo.id) // file perso: niente da caricare
                    return@forEach
                }
                val path = remotePath(projectId, photo.objectId, photo.id)
                api.uploadPhoto(path, local.readBytes())
                api.insertPhotoRow(PhotoRowDto(id = photo.id, objectId = photo.objectId, storagePath = path))
                dao.upsert(photo.copy(storagePath = path, syncStatus = PhotoSyncStatus.SYNCED.wire))
                uploaded++
            }
        }
        val downloaded = pull(projectId)
        PhotoSyncReport(uploaded, deleted, downloaded)
    }

    /**
     * Pull: le foto nuove degli altri membri vengono registrate senza scaricarle; si scarica solo la
     * copertina di ogni oggetto (serve all'elenco). Le altre arrivano quando si apre la scheda.
     */
    private suspend fun pull(projectId: String): Int {
        val remote = api.listPhotos(projectId)
        val local = dao.allForProject(projectId).associateBy { it.id }
        val plan = PhotoMerge.plan(remote, local.values.toList())
        plan.removeLocally.forEach { id ->
            local[id]?.localPath?.let { File(it).delete() }
            dao.delete(id)
        }
        plan.record.forEach { row ->
            dao.upsert(
                PhotoEntity(
                    id = row.id,
                    objectId = row.objectId,
                    localPath = null,
                    storagePath = row.storagePath,
                    syncStatus = PhotoSyncStatus.SYNCED.wire,
                    createdAt = SyncMerge.parseEpochMillis(row.capturedAt) ?: System.currentTimeMillis(),
                )
            )
        }
        var downloaded = 0
        PhotoMerge.coversToDownload(dao.allForProject(projectId)).forEach { photo ->
            if (download(photo)) downloaded++
        }
        trimCache()
        return downloaded
    }

    /**
     * Scheda aperta: scarica le foto dell'oggetto che mancano (in silenzio se offline) e le segna
     * come usate di recente, così la pulizia della cache le tiene.
     */
    suspend fun ensureDownloaded(objectId: String): Unit = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        dao.allForObject(objectId)
            .filter { it.syncStatus != PhotoSyncStatus.PENDING_DELETE.wire }
            .forEach { photo ->
                val file = photo.localPath?.let(::File)
                if (file != null && file.exists()) file.setLastModified(now)
                else runCatching { download(photo) }
            }
        trimCache()
    }

    /** Scarica una foto remota nella memoria privata; false se non più presente sul server. */
    private suspend fun download(photo: PhotoEntity): Boolean {
        val path = photo.storagePath ?: return false
        val bytes = try {
            api.downloadPhoto(path)
        } catch (e: SupabaseException.Http) {
            if (e.status == 404 || e.status == 400) return false else throw e
        }
        val file = File(objectDir(photo.objectId), "${photo.id}.jpg").apply { writeBytes(bytes) }
        dao.setLocalPath(photo.id, file.absolutePath)
        return true
    }

    /** Oltre il limite si liberano le foto usate meno di recente (restano scaricabili dal server). */
    suspend fun trimCache(limitBytes: Long = CACHE_LIMIT_BYTES) = withContext(Dispatchers.IO) {
        val synced = dao.syncedWithLocalFile()
        val covers = PhotoMerge.coverIds(synced)
        val cached = synced.mapNotNull { photo ->
            val file = File(photo.localPath!!)
            if (!file.exists()) { dao.setLocalPath(photo.id, null); return@mapNotNull null }
            PhotoCache.Entry(photo.id, file.length(), file.lastModified(), isCover = photo.id in covers)
        }
        val byId = synced.associateBy { it.id }
        PhotoCache.evict(cached, limitBytes).forEach { id ->
            byId[id]?.localPath?.let { File(it).delete() }
            dao.setLocalPath(id, null)
        }
    }

    private fun objectDir(objectId: String) = File(photosDir, objectId).apply { mkdirs() }

    private fun cleanupCameraTemp(source: Uri) {
        if (source.authority == "${appContext.packageName}.fileprovider") {
            source.lastPathSegment?.let { File(cameraDir, it).delete() }
        }
    }

    companion object {
        /** Spazio massimo per le copie locali di foto già sul server (quelle da caricare non contano). */
        const val CACHE_LIMIT_BYTES = 150L * 1024 * 1024

        fun remotePath(projectId: String, objectId: String, photoId: String) = "$projectId/$objectId/$photoId.jpg"
    }
}

/** Riconciliazione pura (testabile) tra elenco remoto e foto locali di un progetto. */
object PhotoMerge {
    /** [record]: foto remote non ancora note, da registrare (senza file); [removeLocally]: tolte da altri. */
    data class Plan(val record: List<PhotoRowDto>, val removeLocally: List<String>)

    fun plan(remote: List<PhotoRowDto>, local: List<PhotoEntity>): Plan {
        val remoteIds = remote.map { it.id }.toSet()
        val localIds = local.map { it.id }.toSet()
        // Una cancellazione locale pendente resta nota (riga presente): non viene ripristinata.
        val record = remote.filter { it.id !in localIds }
        // Rimossa da un altro membro: elimina la copia locale già sincronizzata (non quelle in upload).
        val removeLocally = local
            .filter { it.syncStatus == PhotoSyncStatus.SYNCED.wire && it.id !in remoteIds }
            .map { it.id }
        return Plan(record, removeLocally)
    }

    /** Prima foto (la più vecchia) di ogni oggetto: è la copertina mostrata nell'elenco. */
    fun coverIds(photos: List<PhotoEntity>): Set<String> =
        photos.filter { it.syncStatus != PhotoSyncStatus.PENDING_DELETE.wire }
            .groupBy { it.objectId }
            .mapNotNull { (_, list) -> list.minWithOrNull(compareBy<PhotoEntity>({ it.createdAt }, { it.id }))?.id }
            .toSet()

    /** Copertine sul server ma non ancora sul telefono. */
    fun coversToDownload(photos: List<PhotoEntity>): List<PhotoEntity> {
        val covers = coverIds(photos)
        return photos.filter { it.id in covers && it.localPath == null && it.storagePath != null }
    }
}

/** Politica della cache foto (pura, testabile): LRU, copertine per ultime. */
object PhotoCache {
    data class Entry(val id: String, val sizeBytes: Long, val lastUsed: Long, val isCover: Boolean)

    fun evict(entries: List<Entry>, limitBytes: Long): List<String> {
        var total = entries.sumOf { it.sizeBytes }
        if (total <= limitBytes) return emptyList()
        val out = mutableListOf<String>()
        entries.sortedWith(compareBy<Entry>({ it.isCover }, { it.lastUsed })).forEach { entry ->
            if (total <= limitBytes) return out
            out += entry.id
            total -= entry.sizeBytes
        }
        return out
    }
}
