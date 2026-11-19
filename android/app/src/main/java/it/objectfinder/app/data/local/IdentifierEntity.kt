package it.objectfinder.app.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Tipi allineati al check di `object_identifiers.identifier_type` (migration 006). */
enum class IdentifierType(val wire: String, val label: String) {
    BARCODE("barcode", "Barcode"),
    SERIAL("serial", "Numero di serie"),
    QR("qr", "QR"),
    EXTERNAL("external", "Codice esterno");

    companion object {
        fun fromWire(value: String): IdentifierType = entries.firstOrNull { it.wire == value } ?: EXTERNAL
        /** Tipi inseribili a mano dall'utente. */
        val manual = listOf(SERIAL, BARCODE, EXTERNAL)
    }
}

/** Codice già presente sull'oggetto (barcode prodotto, seriale, QR di terzi). Immutabile. */
@Entity(tableName = "object_identifiers", indices = [Index("objectId"), Index("value"), Index("syncStatus")])
data class IdentifierEntity(
    @PrimaryKey val id: String,
    val objectId: String,
    val type: String,
    /** Valore normalizzato (vedi [it.objectfinder.app.data.Identifiers.normalize]). */
    val value: String,
    /** Riusa gli stati delle foto: pending_upload / synced / pending_delete. */
    val syncStatus: String = PhotoSyncStatus.PENDING_UPLOAD.wire,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val typeEnum: IdentifierType get() = IdentifierType.fromWire(type)
}

@Dao
interface IdentifierDao {
    @Query("SELECT * FROM object_identifiers WHERE objectId = :objectId AND syncStatus != 'pending_delete' ORDER BY createdAt")
    fun observeForObject(objectId: String): Flow<List<IdentifierEntity>>

    @Query("SELECT * FROM object_identifiers WHERE syncStatus != 'pending_delete'")
    fun observeAllVisible(): Flow<List<IdentifierEntity>>

    /** Oggetti (di qualsiasi progetto locale) che hanno questo codice. */
    @Query(
        "SELECT o.* FROM objects o JOIN object_identifiers i ON i.objectId = o.id " +
            "WHERE i.value = :value AND i.syncStatus != 'pending_delete' GROUP BY o.id ORDER BY o.name"
    )
    suspend fun objectsWithValue(value: String): List<ObjectEntity>

    @Query("SELECT * FROM object_identifiers WHERE id = :id")
    suspend fun getById(id: String): IdentifierEntity?

    @Query("SELECT * FROM object_identifiers WHERE objectId = :objectId")
    suspend fun allForObject(objectId: String): List<IdentifierEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: IdentifierEntity)

    @Query(
        "SELECT i.* FROM object_identifiers i JOIN objects o ON o.id = i.objectId " +
            // Oggetti in conflitto esclusi: se eliminati sul server l'invio fallirebbe (RLS).
            "WHERE o.projectId = :projectId AND i.syncStatus = :status AND o.syncStatus != 'conflict'"
    )
    suspend fun byProjectAndStatus(projectId: String, status: String): List<IdentifierEntity>

    @Query("SELECT i.* FROM object_identifiers i JOIN objects o ON o.id = i.objectId WHERE o.projectId = :projectId")
    suspend fun allForProject(projectId: String): List<IdentifierEntity>

    @Query("SELECT COUNT(*) FROM object_identifiers WHERE syncStatus != 'synced'")
    fun observePendingCount(): Flow<Int>

    @Query("DELETE FROM object_identifiers WHERE id = :id")
    suspend fun delete(id: String)
}
