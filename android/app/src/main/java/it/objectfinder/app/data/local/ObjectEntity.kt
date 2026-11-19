package it.objectfinder.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/** Progetto locale usato prima del login: gli oggetti vengono riassegnati al progetto cloud al bootstrap. */
const val LOCAL_PROJECT_ID = "default-project"

/** Valori allineati all'enum `public.object_status` della migration 001. */
enum class ObjectStatus(val wire: String, val label: String) {
    ACTIVE("active", "Attivo"),
    INACTIVE("inactive", "Inattivo"),
    MAINTENANCE("maintenance", "Manutenzione"),
    ARCHIVED("archived", "Archiviato");

    companion object {
        fun fromWire(value: String): ObjectStatus = entries.firstOrNull { it.wire == value } ?: ACTIVE
    }
}

enum class SyncStatus(val wire: String) {
    PENDING("pending"),
    SYNCED("synced");
}

@Entity(tableName = "objects", indices = [Index("projectId"), Index("syncStatus")])
data class ObjectEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val projectId: String,
    val name: String,
    val category: String,
    val notes: String = "",
    val latitude: Double?,
    val longitude: Double?,
    val accuracyMeters: Float?,
    val status: String = ObjectStatus.ACTIVE.wire,
    val syncStatus: String = SyncStatus.PENDING.wire,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** `updated_at` lato server dell'ultima versione nota (ISO-8601), null se mai sincronizzato. */
    val remoteUpdatedAt: String? = null,
) {
    val hasLocation: Boolean get() = latitude != null && longitude != null
    val statusEnum: ObjectStatus get() = ObjectStatus.fromWire(status)
    val isPending: Boolean get() = syncStatus == SyncStatus.PENDING.wire
}
