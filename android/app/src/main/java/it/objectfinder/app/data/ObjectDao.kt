package it.objectfinder.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ObjectDao {
    @Query("SELECT * FROM objects ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ObjectEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ObjectEntity)

    @Query("SELECT * FROM objects WHERE syncStatus = 'pending'")
    suspend fun pendingSync(): List<ObjectEntity>

    @Query("UPDATE objects SET syncStatus = 'synced' WHERE id = :id")
    suspend fun markSynced(id: String)
}
