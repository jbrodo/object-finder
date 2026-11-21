package it.objectfinder.app.data

import it.objectfinder.app.data.local.ConflictKind
import it.objectfinder.app.data.local.DEFAULT_CATEGORY
import it.objectfinder.app.data.local.PLACE_MAX
import it.objectfinder.app.data.local.LOCAL_PROJECT_ID
import it.objectfinder.app.data.local.LocationSource
import it.objectfinder.app.data.local.ObjectConflictDao
import it.objectfinder.app.data.local.ObjectConflictEntity
import it.objectfinder.app.data.local.ObjectDao
import it.objectfinder.app.data.local.ObjectEntity
import it.objectfinder.app.data.local.ObjectStatus
import it.objectfinder.app.data.local.SyncStatus
import it.objectfinder.app.data.remote.ObjectReadDto
import it.objectfinder.app.data.remote.SupabaseException
import it.objectfinder.app.data.remote.supabaseJson
import it.objectfinder.app.data.remote.SupabaseRestApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

data class SyncReport(val pushed: Int, val pulled: Int, val photos: PhotoSyncReport? = null)

data class ObjectDraft(
    val name: String,
    val category: String,
    val notes: String,
    val status: ObjectStatus,
    val latitude: Double?,
    val longitude: Double?,
    val accuracyMeters: Float?,
    val place: String = "",
    val locationSource: LocationSource = LocationSource.GPS,
    val placeId: String? = null,
)

class ObjectRepository(
    private val dao: ObjectDao,
    private val api: SupabaseRestApi,
    private val auth: AuthRepository,
    private val projects: ProjectRepository,
    private val settings: SettingsStore,
    private val photos: PhotoRepository,
    private val identifiers: IdentifierRepository,
    private val history: LocationHistoryRepository,
    private val conflicts: ObjectConflictDao,
    private val places: PlaceRepository,
    private val placeDao: it.objectfinder.app.data.local.PlaceDao,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val syncMutex = Mutex()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeObjects(): Flow<List<ObjectEntity>> =
        settings.activeProject
            .map { it?.id ?: LOCAL_PROJECT_ID }
            .flatMapLatest { dao.observeByProject(it) }

    fun observeObject(id: String): Flow<ObjectEntity?> = dao.observeById(id)

    /** Modifiche locali in attesa di invio: oggetti + foto. */
    fun observePendingCount(): Flow<Int> =
        combine(
            dao.observePendingCount(),
            photos.observePendingCount(),
            identifiers.observePendingCount(),
            history.observePendingCount(),
        ) { objects, pics, codes, positions -> objects + pics + codes + positions }

    suspend fun get(id: String): ObjectEntity? = dao.getById(id)

    fun observeConflictCount(): Flow<Int> = dao.observeConflictCount()

    fun observeConflict(id: String): Flow<ObjectConflictEntity?> = conflicts.observe(id)

    /** Le modifiche a un oggetto in conflitto restano "la mia versione" finché l'utente non sceglie. */
    private fun statusAfterLocalEdit(existing: ObjectEntity) =
        if (existing.isConflict) SyncStatus.CONFLICT.wire else SyncStatus.PENDING.wire

    suspend fun create(draft: ObjectDraft, id: String = UUID.randomUUID().toString()): ObjectEntity {
        val item = ObjectEntity(
            id = id,
            projectId = projects.currentProjectId,
            name = draft.name.trim(),
            category = draft.category.trim().ifBlank { DEFAULT_CATEGORY },
            notes = draft.notes.trim(),
            place = draft.place.trim().take(PLACE_MAX),
            status = draft.status.wire,
            latitude = draft.latitude,
            longitude = draft.longitude,
            accuracyMeters = draft.accuracyMeters.takeIf { draft.locationSource == LocationSource.GPS },
            locationSource = draft.locationSource.wire,
            placeId = draft.placeId,
            createdAt = now(),
            updatedAt = now(),
        )
        dao.upsert(item)
        recordPosition(item)
        return item
    }

    private suspend fun recordPosition(item: ObjectEntity) {
        val lat = item.latitude ?: return
        val lon = item.longitude ?: return
        history.record(
            item.id, lat, lon, item.accuracyMeters,
            source = if (item.isManualLocation) LocationSource.MANUAL.wire else "phone",
        )
    }

    suspend fun update(id: String, draft: ObjectDraft) {
        val existing = dao.getById(id) ?: return
        val updated =
            existing.copy(
                name = draft.name.trim(),
                category = draft.category.trim().ifBlank { DEFAULT_CATEGORY },
                notes = draft.notes.trim(),
                place = draft.place.trim().take(PLACE_MAX),
                status = draft.status.wire,
                latitude = draft.latitude,
                longitude = draft.longitude,
                accuracyMeters = draft.accuracyMeters.takeIf { draft.locationSource == LocationSource.GPS },
                locationSource = draft.locationSource.wire,
                placeId = draft.placeId,
                syncStatus = statusAfterLocalEdit(existing),
                updatedAt = now(),
            )
        dao.upsert(updated)
        recordPosition(updated)
    }

    /** Archiviazione = soft delete (status `archived`), reversibile e sincronizzata. */
    suspend fun setArchived(id: String, archived: Boolean) {
        val existing = dao.getById(id) ?: return
        val status = if (archived) ObjectStatus.ARCHIVED else ObjectStatus.ACTIVE
        dao.upsert(existing.copy(status = status.wire, syncStatus = statusAfterLocalEdit(existing), updatedAt = now()))
    }

    // ---- Prestiti ----

    /** Presta l'oggetto a [to], con scadenza facoltativa; sincronizzato come ogni altra modifica. */
    suspend fun lend(id: String, to: String, due: java.time.LocalDate?, loanedAt: Long? = null) {
        val existing = dao.getById(id) ?: return
        val name = to.trim().take(LOAN_NAME_MAX)
        require(name.isNotBlank()) { "Indica a chi è prestato" }
        dao.upsert(
            existing.copy(
                loanedTo = name,
                // Annulla di "Restituito": si ripristina la data originale del prestito.
                loanedAt = loanedAt ?: now(),
                loanDue = due?.toString(),
                syncStatus = statusAfterLocalEdit(existing),
                updatedAt = now(),
            )
        )
    }

    /** Oggetto restituito: torna "non prestato". */
    suspend fun returnLoan(id: String) {
        val existing = dao.getById(id) ?: return
        dao.upsert(
            existing.copy(
                loanedTo = "", loanedAt = null, loanDue = null,
                syncStatus = statusAfterLocalEdit(existing), updatedAt = now(),
            )
        )
    }

    /** Nomi usati nei prestiti del progetto, per proporli mentre si scrive. */
    suspend fun recentBorrowers(): List<String> = dao.borrowers(projects.currentProjectId)

    /** Prestiti con scadenza entro [day] (compresa), di tutti i progetti sul telefono. */
    suspend fun loansDueBy(day: java.time.LocalDate): List<ObjectEntity> = dao.loansDueBy(day.toString())

    // ---- Conflitti ----

    /** Tieni la versione locale: sovrascrive quella del server (o ricrea l'oggetto eliminato). */
    suspend fun keepMine(id: String) {
        val item = dao.getById(id) ?: return
        val conflict = conflicts.get(id)
        val server = conflict?.serverJson?.let { supabaseJson.decodeFromString(ObjectReadDto.serializer(), it) }
        dao.upsert(
            item.copy(
                syncStatus = SyncStatus.PENDING.wire,
                remoteVersion = server?.version,       // null + remoteUpdatedAt null → reinserimento
                remoteUpdatedAt = server?.updatedAt,
                updatedAt = now(),
            )
        )
        conflicts.delete(id)
    }

    /** Usa la versione del server: scarta le modifiche locali (o elimina, se eliminato sul server). */
    suspend fun useServer(id: String) {
        val conflict = conflicts.get(id) ?: return
        val server = conflict.serverJson?.let { supabaseJson.decodeFromString(ObjectReadDto.serializer(), it) }
        if (server == null) {
            removeLocally(id)
        } else {
            val local = dao.getById(id)
            SyncMerge.mergeRemote(server, local?.copy(syncStatus = SyncStatus.SYNCED.wire))?.let { dao.upsert(it) }
            server.latitude?.let { lat ->
                server.longitude?.let { lon -> history.record(id, lat, lon, server.accuracyMeters) }
            }
        }
        conflicts.delete(id)
    }

    // ---- Eliminazione definitiva ----

    /**
     * Elimina l'oggetto con foto, codici e storico. Se è già sul server serve la connessione e il
     * ruolo owner/admin (RLS): gli altri dispositivi lo rimuovono al pull (tombstone, migration 009).
     */
    suspend fun deletePermanently(id: String) {
        val item = dao.getById(id) ?: return
        if (item.projectId != LOCAL_PROJECT_ID && !item.neverSynced) {
            if (auth.session.value == null) throw SupabaseException.NotAuthenticated()
            // File su Storage prima della riga (la cascade cancella le righe object_photos, non i file).
            runCatching { api.photoPathsForObject(id) }.getOrDefault(emptyList()).forEach { path ->
                runCatching { api.deletePhotoFile(path) }
            }
            if (!api.deleteObject(id)) throw SupabaseException.Http(403, "Solo proprietario e amministratori possono eliminare")
        }
        removeLocally(id)
    }

    /**
     * Logout: le copie locali degli oggetti cloud già sincronizzati vengono tolte dal telefono
     * (sono sul server; un altro account sullo stesso telefono non le vede né le eredita).
     * Restano solo oggetti con modifiche non inviate, che partono al prossimo accesso allo stesso account.
     */
    suspend fun signOut(): Int = syncMutex.withLock {
        auth.signOut()
        val ids = dao.syncedCloudObjectsWithoutPendingChanges(LOCAL_PROJECT_ID)
        ids.forEach { removeLocally(it) }
        placeDao.deleteSyncedCloud(LOCAL_PROJECT_ID)
        ids.size
    }

    private suspend fun removeLocally(id: String) {
        photos.discardAllForObject(id)
        identifiers.discardAllForObject(id)
        history.discardAllForObject(id)
        conflicts.delete(id)
        dao.delete(id)
    }

    /** Push delle modifiche pending + pull incrementale dal server. Richiede sessione autenticata. */
    suspend fun sync(): Result<SyncReport> = syncMutex.withLock {
        settings.setSyncState(SyncState.Running)
        val result = runCatching {
            if (auth.session.value == null) throw SupabaseException.NotAuthenticated()
            val project = projects.ensureActiveProject()
            // Luoghi prima degli oggetti: un oggetto può riferirsi a un luogo appena creato.
            val placeChanges = places.sync(project.id, canWrite = project.canEditObjects)
            // Un viewer non può scrivere (RLS): si limita al pull.
            val pushed = if (project.canEditObjects) push(project.id) else 0
            val pulled = pull(project.id)
            // Dopo gli oggetti: le policy Storage richiedono che l'oggetto esista sul server.
            val codeChanges = identifiers.sync(project.id, canWrite = project.canEditObjects)
            val positions = if (project.canEditObjects) history.push(project.id) else 0
            val photoReport = photos.sync(project.id, canWrite = project.canEditObjects)
            SyncReport(pushed + codeChanges + positions + placeChanges, pulled, photoReport)
        }
        settings.setSyncState(
            result.fold(
                onSuccess = {
                    SyncState.Done(
                        atMillis = now(),
                        pushed = it.pushed + (it.photos?.let { p -> p.uploaded + p.deleted } ?: 0),
                        pulled = it.pulled + (it.photos?.downloaded ?: 0),
                    )
                },
                onFailure = { SyncState.Failed(now(), it.userMessage("Sincronizzazione non riuscita: riprova")) },
            )
        )
        result
    }

    private suspend fun push(projectId: String): Int {
        var count = 0
        dao.pendingSync(projectId).forEach { item -> if (pushOne(item)) count++ }
        return count
    }

    /** true se inviato; false se è nato un conflitto (o l'oggetto è cambiato durante l'invio). */
    private suspend fun pushOne(item: ObjectEntity): Boolean {
        val dto = SyncMerge.toWriteDto(item)
        val saved: ObjectReadDto? = when {
            item.neverSynced -> try {
                api.insertObject(dto)
            } catch (e: SupabaseException.Http) {
                if (e.status != 409) throw e
                null // già presente (invio precedente non confermato, o creato altrove): si confronta
            }
            item.remoteVersion != null -> api.updateObjectIfVersion(dto, item.remoteVersion)
            else -> null // sincronizzato dalla versione precedente dell'app, senza version: si confronta
        }
        val result = saved ?: when (val mismatch = SyncMerge.resolveMismatch(item, api.getObject(item.id))) {
            is SyncMerge.Mismatch.Retry -> api.updateObjectIfVersion(dto, mismatch.version)
                ?: return openConflict(item, ConflictKind.CHANGED, api.getObject(item.id))
            is SyncMerge.Mismatch.Conflict -> return openConflict(item, ConflictKind.CHANGED, mismatch.server)
            SyncMerge.Mismatch.DeletedRemotely -> return openConflict(item, ConflictKind.DELETED, null)
        }
        return dao.markSynced(item.id, item.updatedAt, result.updatedAt, result.version) > 0
    }

    private suspend fun openConflict(item: ObjectEntity, kind: ConflictKind, server: ObjectReadDto?): Boolean {
        val resolvedKind = if (server == null) ConflictKind.DELETED else kind
        conflicts.upsert(
            ObjectConflictEntity(
                objectId = item.id,
                kind = resolvedKind.wire,
                serverJson = server?.let { supabaseJson.encodeToString(ObjectReadDto.serializer(), it) },
                detectedAt = now(),
            )
        )
        dao.markConflict(item.id)
        return false
    }

    private suspend fun pull(projectId: String): Int {
        // Si riparte un po' prima dell'ultimo cursore (vedi SyncMerge.withLookback).
        var cursor = SyncMerge.withLookback(SyncMerge.PullCursor.decode(settings.pullCursor(projectId)))
        var applied = 0
        while (true) {
            val page = api.objectsUpdatedSince(projectId, cursor, PAGE_SIZE)
            page.forEach { remote ->
                val local = dao.getById(remote.id)
                if (local != null && local.isConflict) {
                    // Conflitto aperto: si aggiorna solo la copia server da mostrare all'utente.
                    conflicts.upsert(
                        ObjectConflictEntity(
                            remote.id, ConflictKind.CHANGED.wire,
                            supabaseJson.encodeToString(ObjectReadDto.serializer(), remote), now(),
                        )
                    )
                    return@forEach
                }
                SyncMerge.mergeRemote(remote, local)?.let {
                    dao.upsert(it)
                    applied++
                }
            }
            cursor = SyncMerge.nextCursor(cursor, page)
            settings.setPullCursor(projectId, cursor?.encode())
            if (page.size < PAGE_SIZE) break
        }
        return applied + pullDeletions(projectId)
    }

    /** Eliminazioni fatte da altri membri: rimuove le copie locali (o apre un conflitto se modificate qui). */
    private suspend fun pullDeletions(projectId: String): Int {
        val key = "del_$projectId"
        var cursor = settings.pullCursor(key)
        val deleted = api.deletedObjectsSince(projectId, SyncMerge.lookbackTimestamp(cursor))
        var removed = 0
        deleted.forEach { tombstone ->
            val local = dao.getById(tombstone.objectId) ?: return@forEach
            if (local.isPending || local.isConflict) {
                openConflict(local, ConflictKind.DELETED, null)
            } else {
                removeLocally(local.id)
                removed++
            }
        }
        cursor = deleted.map { it.deletedAt }.plus(listOfNotNull(cursor))
            .maxByOrNull { SyncMerge.parseEpochMillis(it) ?: Long.MIN_VALUE }
        settings.setPullCursor(key, cursor)
        return removed
    }

    private companion object {
        const val LOAN_NAME_MAX = 120
        const val PAGE_SIZE = 500
    }
}
