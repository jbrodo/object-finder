package it.objectfinder.app.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Luogo del progetto (Casa → Garage → Scaffale); [parentId] null = luogo principale. */
@Entity(tableName = "places", indices = [Index("projectId"), Index("parentId")])
data class PlaceEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val parentId: String?,
    val name: String,
    /** pending (da inviare), synced, pending_delete (da cancellare sul server). */
    val syncStatus: String = PlaceSync.PENDING,
    val updatedAt: Long = System.currentTimeMillis(),
)

object PlaceSync {
    const val PENDING = "pending"
    const val SYNCED = "synced"
    const val PENDING_DELETE = "pending_delete"
}

@Dao
interface PlaceDao {
    @Query("SELECT * FROM places WHERE projectId = :projectId AND syncStatus != 'pending_delete' ORDER BY name COLLATE NOCASE")
    fun observeByProject(projectId: String): Flow<List<PlaceEntity>>

    @Query("SELECT * FROM places WHERE projectId = :projectId AND syncStatus != 'pending_delete'")
    suspend fun allByProject(projectId: String): List<PlaceEntity>

    @Query("SELECT * FROM places WHERE id = :id")
    suspend fun getById(id: String): PlaceEntity?

    @Query("SELECT * FROM places WHERE projectId = :projectId AND syncStatus = :status")
    suspend fun byStatus(projectId: String, status: String): List<PlaceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(place: PlaceEntity)

    @Query("DELETE FROM places WHERE id = :id")
    suspend fun delete(id: String)

    /** Sotto-luoghi del luogo cancellato: salgono di livello (come fa il server). */
    @Query("UPDATE places SET parentId = NULL WHERE parentId = :id")
    suspend fun detachChildren(id: String)

    /** Pull completo: i luoghi già sincronizzati che il server non ha più vengono tolti. */
    @Query("DELETE FROM places WHERE projectId = :projectId AND syncStatus = 'synced' AND id NOT IN (:keep)")
    suspend fun deleteSyncedExcept(projectId: String, keep: List<String>)

    /** Logout: luoghi cloud già sincronizzati tolti dal telefono (restano quelli da inviare). */
    @Query("DELETE FROM places WHERE projectId != :localProject AND syncStatus = 'synced'")
    suspend fun deleteSyncedCloud(localProject: String)

    @Query("UPDATE places SET projectId = :to, syncStatus = 'pending' WHERE projectId = :from")
    suspend fun reassignProject(from: String, to: String): Int
}
