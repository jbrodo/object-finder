package it.objectfinder.app.data

import it.objectfinder.app.data.local.LOCAL_PROJECT_ID
import it.objectfinder.app.data.local.ObjectDao
import it.objectfinder.app.data.local.ObjectEntity
import it.objectfinder.app.data.local.ObjectStatus
import it.objectfinder.app.data.local.SyncStatus
import it.objectfinder.app.data.remote.SupabaseException
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
)

class ObjectRepository(
    private val dao: ObjectDao,
    private val api: SupabaseRestApi,
    private val auth: AuthRepository,
    private val projects: ProjectRepository,
    private val settings: SettingsStore,
    private val photos: PhotoRepository,
    private val identifiers: IdentifierRepository,
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
        ) { objects, pics, codes -> objects + pics + codes }

    suspend fun get(id: String): ObjectEntity? = dao.getById(id)

    suspend fun create(draft: ObjectDraft, id: String = UUID.randomUUID().toString()): ObjectEntity {
        val item = ObjectEntity(
            id = id,
            projectId = projects.currentProjectId,
            name = draft.name.trim(),
            category = draft.category.trim().ifBlank { "general" },
            notes = draft.notes.trim(),
            status = draft.status.wire,
            latitude = draft.latitude,
            longitude = draft.longitude,
            accuracyMeters = draft.accuracyMeters,
            createdAt = now(),
            updatedAt = now(),
        )
        dao.upsert(item)
        return item
    }

    suspend fun update(id: String, draft: ObjectDraft) {
        val existing = dao.getById(id) ?: return
        dao.upsert(
            existing.copy(
                name = draft.name.trim(),
                category = draft.category.trim().ifBlank { "general" },
                notes = draft.notes.trim(),
                status = draft.status.wire,
                latitude = draft.latitude,
                longitude = draft.longitude,
                accuracyMeters = draft.accuracyMeters,
                syncStatus = SyncStatus.PENDING.wire,
                updatedAt = now(),
            )
        )
    }

    /** Archiviazione = soft delete (status `archived`), reversibile e sincronizzata. */
    suspend fun setArchived(id: String, archived: Boolean) {
        val existing = dao.getById(id) ?: return
        val status = if (archived) ObjectStatus.ARCHIVED else ObjectStatus.ACTIVE
        dao.upsert(existing.copy(status = status.wire, syncStatus = SyncStatus.PENDING.wire, updatedAt = now()))
    }

    /** Push delle modifiche pending + pull incrementale dal server. Richiede sessione autenticata. */
    suspend fun sync(): Result<SyncReport> = syncMutex.withLock {
        settings.setSyncState(SyncState.Running)
        val result = runCatching {
            if (auth.session.value == null) throw SupabaseException.NotAuthenticated()
            val project = projects.ensureActiveProject()
            // Un viewer non può scrivere (RLS): si limita al pull.
            val pushed = if (project.canEditObjects) push(project.id) else 0
            val pulled = pull(project.id)
            // Dopo gli oggetti: le policy Storage richiedono che l'oggetto esista sul server.
            val codeChanges = identifiers.sync(project.id, canWrite = project.canEditObjects)
            val photoReport = photos.sync(project.id, canWrite = project.canEditObjects)
            SyncReport(pushed + codeChanges, pulled, photoReport)
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
                onFailure = { SyncState.Failed(now(), it.message ?: it.javaClass.simpleName) },
            )
        )
        result
    }

    private suspend fun push(projectId: String): Int {
        var count = 0
        dao.pendingSync(projectId).forEach { item ->
            val saved = api.upsertObject(SyncMerge.toWriteDto(item))
            if (dao.markSynced(item.id, item.updatedAt, saved.updatedAt) > 0) count++
        }
        return count
    }

    private suspend fun pull(projectId: String): Int {
        var cursor = settings.pullCursor(projectId)
        var applied = 0
        while (true) {
            val page = api.objectsUpdatedSince(projectId, cursor, PAGE_SIZE)
            page.forEach { remote ->
                SyncMerge.mergeRemote(remote, dao.getById(remote.id))?.let {
                    dao.upsert(it)
                    applied++
                }
            }
            cursor = SyncMerge.nextCursor(cursor, page)
            settings.setPullCursor(projectId, cursor)
            if (page.size < PAGE_SIZE) break
        }
        return applied
    }

    private companion object {
        const val PAGE_SIZE = 500
    }
}
