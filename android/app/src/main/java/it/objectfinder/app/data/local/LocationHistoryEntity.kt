package it.objectfinder.app.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Una posizione registrata per un oggetto (storico append-only, tabella `locations`). */
@Entity(tableName = "location_history", indices = [Index("objectId"), Index("syncStatus")])
data class LocationHistoryEntity(
    @PrimaryKey val id: String,
    val objectId: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val recordedAt: Long,
    val source: String = "phone",
    /** pending_upload / synced (riusa gli stati delle foto; nessuna cancellazione dal client). */
    val syncStatus: String = PhotoSyncStatus.PENDING_UPLOAD.wire,
)

@Dao
interface LocationHistoryDao {
    @Query("SELECT * FROM location_history WHERE objectId = :objectId ORDER BY recordedAt DESC LIMIT :limit")
    fun observeForObject(objectId: String, limit: Int = 50): Flow<List<LocationHistoryEntity>>

    @Query("SELECT * FROM location_history WHERE objectId = :objectId ORDER BY recordedAt DESC LIMIT 1")
    suspend fun latestForObject(objectId: String): LocationHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: LocationHistoryEntity)

    /** Inserimento dal pull: non sovrascrive righe locali con lo stesso id. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(item: LocationHistoryEntity)

    @Query(
        "SELECT h.* FROM location_history h JOIN objects o ON o.id = h.objectId " +
            "WHERE o.projectId = :projectId AND h.syncStatus = 'pending_upload'"
    )
    suspend fun pendingForProject(projectId: String): List<LocationHistoryEntity>

    @Query("UPDATE location_history SET syncStatus = 'synced' WHERE id = :id")
    suspend fun markSynced(id: String)

    @Query("SELECT COUNT(*) FROM location_history WHERE syncStatus != 'synced'")
    fun observePendingCount(): Flow<Int>

    @Query("DELETE FROM location_history WHERE objectId = :objectId")
    suspend fun deleteForObject(objectId: String)
}
