package it.objectfinder.app.data

import kotlinx.coroutines.flow.Flow

class ObjectRepository(
    private val dao: ObjectDao,
    private val api: SupabaseApi
) {
    fun observeObjects(): Flow<List<ObjectEntity>> = dao.observeAll()

    suspend fun create(item: ObjectEntity) {
        dao.upsert(item)
        // La sincronizzazione cloud viene demandata al Worker.
    }

    suspend fun syncPending(): Result<Int> = runCatching {
        val pending = dao.pendingSync()
        var count = 0
        pending.forEach { item ->
            api.upsertObject(item.toDto())
            dao.markSynced(item.id)
            count++
        }
        count
    }
}
