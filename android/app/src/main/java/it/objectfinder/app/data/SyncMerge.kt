package it.objectfinder.app.data

import it.objectfinder.app.data.local.ObjectEntity
import it.objectfinder.app.data.local.ObjectStatus
import it.objectfinder.app.data.local.SyncStatus
import it.objectfinder.app.data.remote.ObjectReadDto
import it.objectfinder.app.data.remote.ObjectWriteDto
import java.time.OffsetDateTime

/**
 * Regole di sincronizzazione (pure, testabili):
 * - pull: una modifica locale non inviata o un conflitto aperto non vengono sovrascritti;
 *   altrimenti la versione server sostituisce quella locale;
 * - push: concorrenza ottimistica sulla colonna `version`; se il server è cambiato nel frattempo
 *   per mano di un altro membro si apre un conflitto che sceglie l'utente.
 */
object SyncMerge {
    fun mergeRemote(remote: ObjectReadDto, local: ObjectEntity?): ObjectEntity? {
        // Modifiche locali non inviate o conflitto aperto: la copia locale non va toccata.
        if (local != null && (local.isPending || local.isConflict)) return null
        // Già nota (es. riletta nella finestra di ricontrollo del cursore): niente da applicare.
        if (local != null && local.remoteUpdatedAt == remote.updatedAt && local.remoteVersion == remote.version) return null
        return ObjectEntity(
            id = remote.id,
            projectId = remote.projectId,
            name = remote.name,
            category = remote.category,
            notes = remote.notes,
            place = remote.place,
            latitude = remote.latitude,
            longitude = remote.longitude,
            accuracyMeters = remote.accuracyMeters,
            locationSource = remote.locationSource,
            status = remote.status,
            syncStatus = SyncStatus.SYNCED.wire,
            createdAt = local?.createdAt ?: parseEpochMillis(remote.createdAt) ?: System.currentTimeMillis(),
            updatedAt = parseEpochMillis(remote.updatedAt) ?: System.currentTimeMillis(),
            remoteUpdatedAt = remote.updatedAt,
            remoteVersion = remote.version,
        )
    }

    sealed interface Mismatch {
        /** Il server non ha cambiato l'oggetto (o ha lo stesso contenuto): si riprova con la sua versione. */
        data class Retry(val version: Int) : Mismatch
        data class Conflict(val server: ObjectReadDto) : Mismatch
        /** Eliminato sul server (o non più accessibile) mentre qui c'erano modifiche. */
        data object DeletedRemotely : Mismatch
    }

    /**
     * Decide cosa fare quando l'aggiornamento condizionato non ha toccato righe (versione diversa)
     * o l'inserimento ha trovato l'oggetto già esistente. Un conflitto è solo un vero cambiamento
     * di un altro membro: copia server invariata dall'ultima sync (stesso updated_at, anche per i
     * dati della versione precedente dell'app senza version) o contenuto identico non lo sono.
     */
    fun resolveMismatch(local: ObjectEntity, server: ObjectReadDto?): Mismatch = when {
        server == null -> Mismatch.DeletedRemotely
        local.remoteUpdatedAt != null && server.updatedAt == local.remoteUpdatedAt -> Mismatch.Retry(server.version)
        sameContent(local, server) -> Mismatch.Retry(server.version)
        else -> Mismatch.Conflict(server)
    }

    fun sameContent(local: ObjectEntity, server: ObjectReadDto): Boolean =
        local.name == server.name &&
            local.category == server.category &&
            local.notes == server.notes &&
            local.place == server.place &&
            local.status == server.status &&
            local.latitude == server.latitude &&
            local.longitude == server.longitude &&
            local.accuracyMeters == server.accuracyMeters &&
            local.locationSource == server.locationSource

    data class FieldDiff(val label: String, val mine: String, val theirs: String)

    /** Campi diversi tra la versione locale e quella del server, per la schermata di scelta. */
    fun diff(local: ObjectEntity, server: ObjectReadDto): List<FieldDiff> {
        fun pos(lat: Double?, lon: Double?, source: String) =
            if (lat == null || lon == null) "nessuna"
            else String.format(java.util.Locale.ROOT, "%.5f, %.5f", lat, lon) + if (source == "manual") " (a mano)" else ""
        return listOfNotNull(
            FieldDiff("Nome", local.name, server.name).takeIf { it.mine != it.theirs },
            FieldDiff("Categoria", local.category, server.category).takeIf { it.mine != it.theirs },
            FieldDiff(
                "Stato",
                ObjectStatus.fromWire(local.status).label,
                ObjectStatus.fromWire(server.status).label,
            ).takeIf { it.mine != it.theirs },
            FieldDiff("Dove", local.place.ifBlank { "—" }, server.place.ifBlank { "—" }).takeIf { it.mine != it.theirs },
            FieldDiff("Note", local.notes.ifBlank { "—" }, server.notes.ifBlank { "—" }).takeIf { it.mine != it.theirs },
            FieldDiff(
                "Posizione",
                pos(local.latitude, local.longitude, local.locationSource),
                pos(server.latitude, server.longitude, server.locationSource),
            ).takeIf { it.mine != it.theirs },
        )
    }

    /**
     * Cursore di pull composto `(updated_at, id)`: righe con lo stesso `updated_at` a cavallo di due
     * pagine non vengono saltate. Si salva come "updated_at|id"; un cursore delle versioni ≤ 1.2
     * (solo timestamp) vale come id vuoto.
     */
    data class PullCursor(val updatedAt: String, val id: String = "") {
        fun encode(): String = "$updatedAt|$id"

        companion object {
            fun decode(raw: String?): PullCursor? {
                if (raw.isNullOrBlank()) return null
                val at = raw.substringBefore('|')
                return PullCursor(at, raw.substringAfter('|', ""))
            }
        }
    }

    /** Ultima riga osservata (ordine cronologico, poi id), o il cursore attuale se la pagina è vuota. */
    fun nextCursor(current: PullCursor?, page: List<ObjectReadDto>): PullCursor? =
        (page.map { PullCursor(it.updatedAt, it.id) } + listOfNotNull(current))
            .maxWithOrNull(compareBy<PullCursor>({ parseEpochMicros(it.updatedAt) ?: Long.MIN_VALUE }, { it.id }))

    /**
     * Punto di partenza di un nuovo pull: qualche minuto prima dell'ultimo cursore. `updated_at` è
     * l'ora di inizio della transazione sul server, quindi una scrittura lunga può diventare visibile
     * dopo che il cursore l'ha già superata; ripassare la finestra la recupera (il merge è idempotente).
     */
    fun withLookback(cursor: PullCursor?, seconds: Long = LOOKBACK_SECONDS): PullCursor? {
        val at = cursor?.updatedAt ?: return null
        val time = runCatching { OffsetDateTime.parse(at) }.getOrNull() ?: return cursor
        return PullCursor(time.minusSeconds(seconds).toString(), "")
    }

    /** Stessa finestra per le eliminazioni (cursore solo timestamp). */
    fun lookbackTimestamp(at: String?, seconds: Long = LOOKBACK_SECONDS): String? {
        if (at == null) return null
        return runCatching { OffsetDateTime.parse(at).minusSeconds(seconds).toString() }.getOrDefault(at)
    }

    const val LOOKBACK_SECONDS = 120L

    fun toWriteDto(entity: ObjectEntity) = ObjectWriteDto(
        id = entity.id,
        projectId = entity.projectId,
        name = entity.name,
        category = entity.category,
        notes = entity.notes,
        place = entity.place,
        status = entity.status,
        latitude = entity.latitude,
        longitude = entity.longitude,
        accuracyMeters = entity.accuracyMeters,
        locationSource = entity.locationSource,
    )

    fun parseEpochMillis(iso: String?): Long? =
        iso?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

    private fun parseEpochMicros(iso: String): Long? = runCatching {
        val instant = OffsetDateTime.parse(iso).toInstant()
        instant.epochSecond * 1_000_000 + instant.nano / 1_000
    }.getOrNull()
}
