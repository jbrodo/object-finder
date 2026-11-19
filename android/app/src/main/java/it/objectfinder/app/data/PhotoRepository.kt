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

    private suspend fun pull(projectId: String): Int {
        val remote = api.listPhotos(projectId)
        val local = dao.allForProject(projectId).associateBy { it.id }
        val plan = PhotoMerge.plan(remote, local.values.toList())
        plan.removeLocally.forEach { id ->
            local[id]?.localPath?.let { File(it).delete() }
            dao.delete(id)
        }
        var downloaded = 0
        plan.download.forEach { row ->
            val bytes = try {
                api.downloadPhoto(row.storagePath)
            } catch (e: SupabaseException.Http) {
                if (e.status == 404 || e.status == 400) return@forEach else throw e
            }
            val file = File(objectDir(row.objectId), "${row.id}.jpg").apply { writeBytes(bytes) }
            dao.upsert(
                PhotoEntity(
                    id = row.id,
                    objectId = row.objectId,
                    localPath = file.absolutePath,
                    storagePath = row.storagePath,
                    syncStatus = PhotoSyncStatus.SYNCED.wire,
                    createdAt = local[row.id]?.createdAt ?: System.currentTimeMillis(),
                )
            )
            downloaded++
        }
        return downloaded
    }

    private fun objectDir(objectId: String) = File(photosDir, objectId).apply { mkdirs() }

    private fun cleanupCameraTemp(source: Uri) {
        if (source.authority == "${appContext.packageName}.fileprovider") {
            source.lastPathSegment?.let { File(cameraDir, it).delete() }
        }
    }

    companion object {
        fun remotePath(projectId: String, objectId: String, photoId: String) = "$projectId/$objectId/$photoId.jpg"
    }
}

/** Riconciliazione pura (testabile) tra elenco remoto e foto locali di un progetto. */
object PhotoMerge {
    data class Plan(val download: List<PhotoRowDto>, val removeLocally: List<String>)

    fun plan(remote: List<PhotoRowDto>, local: List<PhotoEntity>): Plan {
        val remoteIds = remote.map { it.id }.toSet()
        val localById = local.associateBy { it.id }
        val download = remote.filter { row ->
            val existing = localById[row.id]
            // Nuova, oppure nota ma senza file locale; una cancellazione locale pendente vince.
            existing == null || (existing.localPath == null && existing.syncStatus != PhotoSyncStatus.PENDING_DELETE.wire)
        }
        // Rimossa da un altro membro: elimina la copia locale già sincronizzata (non quelle in upload).
        val removeLocally = local
            .filter { it.syncStatus == PhotoSyncStatus.SYNCED.wire && it.id !in remoteIds }
            .map { it.id }
        return Plan(download, removeLocally)
    }
}
