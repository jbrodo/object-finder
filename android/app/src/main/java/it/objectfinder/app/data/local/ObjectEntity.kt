package it.objectfinder.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/** Progetto locale usato prima del login: gli oggetti vengono riassegnati al progetto cloud al bootstrap. */
const val LOCAL_PROJECT_ID = "default-project"

/** Categoria predefinita. Gli oggetti creati prima della 1.1 hanno il valore legacy "general". */
const val DEFAULT_CATEGORY = "Generale"

/** Lunghezza massima del campo "Dove" (vincolo objects_place_length della migration 010). */
const val PLACE_MAX = 200

/** Etichetta mostrata per una categoria (il valore legacy inglese viene localizzato). */
fun categoryLabel(raw: String): String = if (isDefaultCategory(raw)) DEFAULT_CATEGORY else raw

fun isDefaultCategory(raw: String): Boolean =
    raw.isBlank() || raw.equals("general", ignoreCase = true) || raw == DEFAULT_CATEGORY

/** Valori allineati all'enum `public.object_status` della migration 001. */
enum class ObjectStatus(val wire: String, val label: String) {
    ACTIVE("active", "In uso"),
    INACTIVE("inactive", "Non in uso"),
    MAINTENANCE("maintenance", "In manutenzione"),
    ARCHIVED("archived", "Archiviato");

    companion object {
        fun fromWire(value: String): ObjectStatus = entries.firstOrNull { it.wire == value } ?: ACTIVE
    }
}

/** Valori allineati al vincolo objects_location_source_valid della migration 012. */
enum class LocationSource(val wire: String) {
    GPS("gps"),
    MANUAL("manual");

    companion object {
        fun fromWire(value: String?): LocationSource = entries.firstOrNull { it.wire == value } ?: GPS
    }
}

/** Oltre questa incertezza il punto GPS è "approssimato" (al chiuso è frequente). */
const val APPROXIMATE_ACCURACY_METERS = 25f

enum class SyncStatus(val wire: String) {
    PENDING("pending"),
    SYNCED("synced"),
    /** Modificato anche sul server da un altro membro: serve una scelta dell'utente (vedi ObjectConflictEntity). */
    CONFLICT("conflict");
}

@Entity(tableName = "objects", indices = [Index("projectId"), Index("syncStatus")])
data class ObjectEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val projectId: String,
    val name: String,
    val category: String,
    val notes: String = "",
    /** "Dove": descrizione libera del punto (es. "garage, scaffale alto"). */
    val place: String = "",
    val latitude: Double?,
    val longitude: Double?,
    val accuracyMeters: Float?,
    /** Origine del punto: [LocationSource.GPS] (con incertezza) o [LocationSource.MANUAL] (scelto sulla mappa). */
    val locationSource: String = LocationSource.GPS.wire,
    val status: String = ObjectStatus.ACTIVE.wire,
    /** Prestito: a chi ("" = non prestato), da quando (epoch ms) e scadenza facoltativa (yyyy-MM-dd). */
    val loanedTo: String = "",
    val loanedAt: Long? = null,
    val loanDue: String? = null,
    val syncStatus: String = SyncStatus.PENDING.wire,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** `updated_at` lato server dell'ultima versione nota (ISO-8601), null se mai sincronizzato. */
    val remoteUpdatedAt: String? = null,
    /** `version` lato server dell'ultima copia nota: base della concorrenza ottimistica. */
    val remoteVersion: Int? = null,
) {
    val isConflict: Boolean get() = syncStatus == SyncStatus.CONFLICT.wire
    /** Mai arrivato sul server: si può eliminare senza connessione. */
    val neverSynced: Boolean get() = remoteVersion == null && remoteUpdatedAt == null
    val hasLocation: Boolean get() = latitude != null && longitude != null
    val isManualLocation: Boolean get() = LocationSource.fromWire(locationSource) == LocationSource.MANUAL
    /** Punto GPS con incertezza alta: va mostrato come tale. */
    val isApproximate: Boolean
        get() = hasLocation && !isManualLocation && (accuracyMeters ?: 0f) > APPROXIMATE_ACCURACY_METERS
    val statusEnum: ObjectStatus get() = ObjectStatus.fromWire(status)
    val isPending: Boolean get() = syncStatus == SyncStatus.PENDING.wire
    val isLoaned: Boolean get() = loanedTo.isNotBlank()
}
