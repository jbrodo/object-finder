package it.objectfinder.app.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Stato di sincronizzazione di una foto (le foto sono immutabili: niente update). */
enum class PhotoSyncStatus(val wire: String) {
    /** Scattata/importata in locale, da caricare. */
    PENDING_UPLOAD("pending_upload"),
    SYNCED("synced"),
    /** Eliminata dall'utente: da cancellare sul server, poi rimossa in locale. */
    PENDING_DELETE("pending_delete"),
}

@Entity(tableName = "object_photos", indices = [Index("objectId"), Index("syncStatus")])
data class PhotoEntity(
    @PrimaryKey val id: String,
    val objectId: String,
    /** File JPEG nella memoria privata dell'app; null se remota e non ancora scaricata. */
    val localPath: String?,
    /** `<project>/<object>/<id>.jpg` nel bucket `object-photos`; assegnato al primo upload. */
    val storagePath: String? = null,
    val syncStatus: String = PhotoSyncStatus.PENDING_UPLOAD.wire,
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface PhotoDao {
    @Query("SELECT * FROM object_photos WHERE objectId = :objectId AND syncStatus != 'pending_delete' ORDER BY createdAt")
    fun observeForObject(objectId: String): Flow<List<PhotoEntity>>

    /** Prima foto disponibile in locale per ogni oggetto (copertina nell'elenco). */
    @Query(
        "SELECT * FROM object_photos WHERE localPath IS NOT NULL AND syncStatus != 'pending_delete' " +
            "ORDER BY createdAt"
    )
    fun observeAllVisible(): Flow<List<PhotoEntity>>

    @Query("SELECT * FROM object_photos WHERE id = :id")
    suspend fun getById(id: String): PhotoEntity?

    @Query("SELECT * FROM object_photos WHERE objectId = :objectId")
    suspend fun allForObject(objectId: String): List<PhotoEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(photo: PhotoEntity)

    @Query(
        "SELECT p.* FROM object_photos p JOIN objects o ON o.id = p.objectId " +
            // Oggetti in conflitto esclusi: se eliminati sul server l'invio fallirebbe (RLS).
            "WHERE o.projectId = :projectId AND p.syncStatus = :status AND o.syncStatus != 'conflict'"
    )
    suspend fun byProjectAndStatus(projectId: String, status: String): List<PhotoEntity>

    @Query(
        "SELECT p.* FROM object_photos p JOIN objects o ON o.id = p.objectId WHERE o.projectId = :projectId"
    )
    suspend fun allForProject(projectId: String): List<PhotoEntity>

    @Query("SELECT COUNT(*) FROM object_photos WHERE syncStatus != 'synced'")
    fun observePendingCount(): Flow<Int>

    /** Foto già sul server con copia locale: le uniche che la cache può liberare. */
    @Query("SELECT * FROM object_photos WHERE syncStatus = 'synced' AND localPath IS NOT NULL")
    suspend fun syncedWithLocalFile(): List<PhotoEntity>

    @Query("UPDATE object_photos SET localPath = :path WHERE id = :id")
    suspend fun setLocalPath(id: String, path: String?)

    @Query("DELETE FROM object_photos WHERE id = :id")
    suspend fun delete(id: String)
}
