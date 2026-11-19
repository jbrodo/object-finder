package it.objectfinder.app.data

import it.objectfinder.app.data.local.IdentifierDao
import it.objectfinder.app.data.local.IdentifierEntity
import it.objectfinder.app.data.local.IdentifierType
import it.objectfinder.app.data.local.ObjectEntity
import it.objectfinder.app.data.local.PhotoSyncStatus
import it.objectfinder.app.data.remote.IdentifierRowDto
import it.objectfinder.app.data.remote.SupabaseRestApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

object Identifiers {
    const val MAX_LENGTH = 512

    /**
     * Normalizzazione per un confronto affidabile tra codice salvato e codice scansionato:
     * spazi esterni rimossi, spazi interni compattati; i seriali sono case-insensitive
     * (stampati spesso in maiuscolo ma digitati in minuscolo).
     */
    fun normalize(type: IdentifierType, raw: String): String {
        val compact = raw.trim().replace(Regex("\\s+"), " ")
        return if (type == IdentifierType.SERIAL) compact.uppercase() else compact
    }

    /** Valori da cercare per un codice scansionato (il tipo non è noto: si prova anche come seriale). */
    fun lookupKeys(raw: String): List<String> =
        listOf(normalize(IdentifierType.BARCODE, raw), normalize(IdentifierType.SERIAL, raw)).distinct()
}

sealed interface AddIdentifierResult {
    data object Added : AddIdentifierResult
    data object Duplicate : AddIdentifierResult
    data object Invalid : AddIdentifierResult
}

class IdentifierRepository(
    private val dao: IdentifierDao,
    private val api: SupabaseRestApi,
) {
    fun observeForObject(objectId: String): Flow<List<IdentifierEntity>> = dao.observeForObject(objectId)

    /** objectId → valori dei codici (per la ricerca in elenco). */
    fun observeValuesByObject(): Flow<Map<String, List<String>>> = dao.observeAllVisible().map { list ->
        list.groupBy({ it.objectId }, { it.value })
    }

    fun observePendingCount(): Flow<Int> = dao.observePendingCount()

    suspend fun add(objectId: String, type: IdentifierType, raw: String): AddIdentifierResult {
        val value = Identifiers.normalize(type, raw)
        if (value.isEmpty() || value.length > Identifiers.MAX_LENGTH) return AddIdentifierResult.Invalid
        val existing = dao.allForObject(objectId)
        val duplicate = existing.firstOrNull { it.type == type.wire && it.value == value }
        if (duplicate != null) {
            // Ricollegare un codice appena rimosso (non ancora sincronizzato) lo ripristina.
            if (duplicate.syncStatus != PhotoSyncStatus.PENDING_DELETE.wire) return AddIdentifierResult.Duplicate
            dao.upsert(duplicate.copy(syncStatus = PhotoSyncStatus.SYNCED.wire))
            return AddIdentifierResult.Added
        }
        dao.upsert(IdentifierEntity(id = UUID.randomUUID().toString(), objectId = objectId, type = type.wire, value = value))
        return AddIdentifierResult.Added
    }

    suspend fun delete(id: String) {
        val item = dao.getById(id) ?: return
        if (item.syncStatus == PhotoSyncStatus.PENDING_UPLOAD.wire) dao.delete(id)
        else dao.upsert(item.copy(syncStatus = PhotoSyncStatus.PENDING_DELETE.wire))
    }

    suspend fun discardAllForObject(objectId: String) {
        dao.allForObject(objectId).forEach { dao.delete(it.id) }
    }

    /** Oggetti locali che hanno il codice scansionato. */
    suspend fun findObjects(raw: String): List<ObjectEntity> =
        Identifiers.lookupKeys(raw).flatMap { dao.objectsWithValue(it) }.distinctBy { it.id }

    /** Da chiamare dopo il push degli oggetti (le policy richiedono che l'oggetto esista). */
    suspend fun sync(projectId: String, canWrite: Boolean): Int {
        var changes = 0
        if (canWrite) {
            dao.byProjectAndStatus(projectId, PhotoSyncStatus.PENDING_DELETE.wire).forEach {
                api.deleteIdentifier(it.id)
                dao.delete(it.id)
                changes++
            }
            dao.byProjectAndStatus(projectId, PhotoSyncStatus.PENDING_UPLOAD.wire).forEach {
                api.insertIdentifier(IdentifierRowDto(it.id, it.objectId, it.type, it.value))
                dao.upsert(it.copy(syncStatus = PhotoSyncStatus.SYNCED.wire))
                changes++
            }
        }
        val plan = IdentifierMerge.plan(api.listIdentifiers(projectId), dao.allForProject(projectId))
        plan.removeLocally.forEach { dao.delete(it) }
        plan.insertLocally.forEach { dao.upsert(it) }
        return changes + plan.removeLocally.size + plan.insertLocally.size
    }
}

/** Riconciliazione pura tra codici remoti e locali di un progetto. */
object IdentifierMerge {
    data class Plan(val insertLocally: List<IdentifierEntity>, val removeLocally: List<String>)

    fun plan(remote: List<IdentifierRowDto>, local: List<IdentifierEntity>): Plan {
        val localIds = local.map { it.id }.toSet()
        val remoteIds = remote.map { it.id }.toSet()
        val insert = remote.filter { it.id !in localIds }.map {
            IdentifierEntity(it.id, it.objectId, it.type, it.value, PhotoSyncStatus.SYNCED.wire)
        }
        // Rimosso da un altro membro: si elimina la copia locale già sincronizzata.
        val remove = local.filter { it.syncStatus == PhotoSyncStatus.SYNCED.wire && it.id !in remoteIds }.map { it.id }
        return Plan(insert, remove)
    }
}
