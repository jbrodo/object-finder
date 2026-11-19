package it.objectfinder.app.data

import it.objectfinder.app.data.local.ObjectEntity
import it.objectfinder.app.data.local.SyncStatus
import it.objectfinder.app.data.remote.ObjectReadDto
import it.objectfinder.app.data.remote.ObjectWriteDto
import java.time.OffsetDateTime

/**
 * Regole di merge pull (pure, testabili):
 * - una modifica locale non ancora inviata (pending) vince: verrà spinta al prossimo push;
 * - altrimenti la versione server sovrascrive quella locale.
 * Conflitti multi-utente più fini (version check) sono previsti nella milestone successiva.
 */
object SyncMerge {
    fun mergeRemote(remote: ObjectReadDto, local: ObjectEntity?): ObjectEntity? {
        if (local != null && local.isPending) return null
        return ObjectEntity(
            id = remote.id,
            projectId = remote.projectId,
            name = remote.name,
            category = remote.category,
            notes = remote.notes,
            latitude = remote.latitude,
            longitude = remote.longitude,
            accuracyMeters = remote.accuracyMeters,
            status = remote.status,
            syncStatus = SyncStatus.SYNCED.wire,
            createdAt = local?.createdAt ?: parseEpochMillis(remote.createdAt) ?: System.currentTimeMillis(),
            updatedAt = parseEpochMillis(remote.updatedAt) ?: System.currentTimeMillis(),
            remoteUpdatedAt = remote.updatedAt,
        )
    }

    /** Cursore = `updated_at` massimo osservato (confronto cronologico, non lessicografico). */
    fun nextCursor(current: String?, page: List<ObjectReadDto>): String? =
        (page.map { it.updatedAt } + listOfNotNull(current))
            .maxByOrNull { parseEpochMicros(it) ?: Long.MIN_VALUE }

    fun toWriteDto(entity: ObjectEntity) = ObjectWriteDto(
        id = entity.id,
        projectId = entity.projectId,
        name = entity.name,
        category = entity.category,
        notes = entity.notes,
        status = entity.status,
        latitude = entity.latitude,
        longitude = entity.longitude,
        accuracyMeters = entity.accuracyMeters,
    )

    fun parseEpochMillis(iso: String?): Long? =
        iso?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

    private fun parseEpochMicros(iso: String): Long? = runCatching {
        val instant = OffsetDateTime.parse(iso).toInstant()
        instant.epochSecond * 1_000_000 + instant.nano / 1_000
    }.getOrNull()
}
