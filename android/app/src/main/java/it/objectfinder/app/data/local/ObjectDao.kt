package it.objectfinder.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ObjectDao {
    @Query("SELECT * FROM objects WHERE projectId = :projectId ORDER BY updatedAt DESC")
    fun observeByProject(projectId: String): Flow<List<ObjectEntity>>

    @Query("SELECT * FROM objects WHERE id = :id")
    fun observeById(id: String): Flow<ObjectEntity?>

    @Query("SELECT * FROM objects WHERE id = :id")
    suspend fun getById(id: String): ObjectEntity?

    @Query("SELECT * FROM objects WHERE projectId = :projectId ORDER BY name COLLATE NOCASE")
    suspend fun allByProject(projectId: String): List<ObjectEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ObjectEntity)

    @Query("SELECT * FROM objects WHERE syncStatus = 'pending' AND projectId = :projectId")
    suspend fun pendingSync(projectId: String): List<ObjectEntity>

    @Query("SELECT COUNT(*) FROM objects WHERE syncStatus = 'pending'")
    fun observePendingCount(): Flow<Int>

    /**
     * Marca come sincronizzato solo se l'oggetto non è stato modificato durante il push
     * (stesso `updatedAt` locale): evita di perdere una modifica concorrente.
     */
    @Query(
        "UPDATE objects SET syncStatus = 'synced', remoteUpdatedAt = :remoteUpdatedAt, remoteVersion = :remoteVersion " +
            "WHERE id = :id AND updatedAt = :localUpdatedAt"
    )
    suspend fun markSynced(id: String, localUpdatedAt: Long, remoteUpdatedAt: String?, remoteVersion: Int?): Int

    @Query("UPDATE objects SET syncStatus = 'conflict' WHERE id = :id")
    suspend fun markConflict(id: String)

    @Query("SELECT COUNT(*) FROM objects WHERE syncStatus = 'conflict'")
    fun observeConflictCount(): Flow<Int>

    @Query("DELETE FROM objects WHERE id = :id")
    suspend fun delete(id: String)

    /** Riassegna gli oggetti creati offline al progetto cloud attivo e li rimette in coda di sync. */
    @Query("UPDATE objects SET projectId = :to, syncStatus = 'pending' WHERE projectId = :from")
    suspend fun reassignProject(from: String, to: String): Int
}
