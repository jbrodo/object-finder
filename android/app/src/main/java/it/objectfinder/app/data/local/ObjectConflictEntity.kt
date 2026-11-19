package it.objectfinder.app.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

enum class ConflictKind(val wire: String) {
    /** Un altro membro ha modificato l'oggetto dopo l'ultima sincronizzazione di questo dispositivo. */
    CHANGED("changed"),
    /** L'oggetto è stato eliminato sul server mentre qui c'erano modifiche non inviate. */
    DELETED("deleted");

    companion object {
        fun fromWire(value: String) = entries.firstOrNull { it.wire == value } ?: CHANGED
    }
}

/** Copia server in conflitto con la versione locale, in attesa della scelta dell'utente. */
@Entity(tableName = "object_conflicts")
data class ObjectConflictEntity(
    @PrimaryKey val objectId: String,
    val kind: String,
    /** ObjectReadDto serializzato (null se l'oggetto è stato eliminato sul server). */
    val serverJson: String?,
    val detectedAt: Long = System.currentTimeMillis(),
)

@Dao
interface ObjectConflictDao {
    @Query("SELECT * FROM object_conflicts WHERE objectId = :objectId")
    fun observe(objectId: String): Flow<ObjectConflictEntity?>

    @Query("SELECT * FROM object_conflicts WHERE objectId = :objectId")
    suspend fun get(objectId: String): ObjectConflictEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ObjectConflictEntity)

    @Query("DELETE FROM object_conflicts WHERE objectId = :objectId")
    suspend fun delete(objectId: String)
}
