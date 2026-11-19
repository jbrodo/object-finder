package it.objectfinder.app.data

import it.objectfinder.app.data.local.LocationHistoryDao
import it.objectfinder.app.data.local.LocationHistoryEntity
import it.objectfinder.app.data.local.PhotoSyncStatus
import it.objectfinder.app.data.remote.LocationRowDto
import it.objectfinder.app.data.remote.SupabaseRestApi
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.util.UUID
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Distanze e regole pure (testabili). */
object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Distanza in metri (formula dell'haversine): sufficiente per distanze "da oggetto smarrito". */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    fun formatDistance(meters: Double): String = when {
        meters < 1_000 -> "${meters.toInt()} m"
        meters < 10_000 -> String.format(java.util.Locale.ITALY, "%.1f km", meters / 1_000)
        else -> "${(meters / 1_000).toInt()} km"
    }

    /**
     * Una nuova posizione va registrata nello storico solo se è davvero cambiata:
     * spostamenti sotto [minMoveMeters] (o sotto l'incertezza del GPS) sono rumore.
     */
    fun isNewPosition(
        previous: LocationHistoryEntity?,
        latitude: Double,
        longitude: Double,
        accuracyMeters: Float?,
        minMoveMeters: Double = 10.0,
    ): Boolean {
        if (previous == null) return true
        val moved = distanceMeters(previous.latitude, previous.longitude, latitude, longitude)
        val noise = maxOf(minMoveMeters, (accuracyMeters ?: 0f).toDouble(), (previous.accuracyMeters ?: 0f).toDouble())
        return moved > noise
    }
}

class LocationHistoryRepository(
    private val dao: LocationHistoryDao,
    private val api: SupabaseRestApi,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    fun observeForObject(objectId: String): Flow<List<LocationHistoryEntity>> = dao.observeForObject(objectId)

    fun observePendingCount(): Flow<Int> = dao.observePendingCount()

    /** Registra la posizione nello storico se è cambiata rispetto all'ultima nota. */
    suspend fun record(objectId: String, latitude: Double, longitude: Double, accuracyMeters: Float?) {
        if (!Geo.isNewPosition(dao.latestForObject(objectId), latitude, longitude, accuracyMeters)) return
        dao.upsert(
            LocationHistoryEntity(
                id = UUID.randomUUID().toString(),
                objectId = objectId,
                latitude = latitude,
                longitude = longitude,
                accuracyMeters = accuracyMeters,
                recordedAt = now(),
            )
        )
    }

    suspend fun discardAllForObject(objectId: String) = dao.deleteForObject(objectId)

    /** Push delle posizioni locali (dopo il push degli oggetti: la policy richiede che esistano). */
    suspend fun push(projectId: String): Int {
        var count = 0
        dao.pendingForProject(projectId).forEach { item ->
            api.insertLocation(
                LocationRowDto(
                    id = item.id,
                    objectId = item.objectId,
                    source = item.source,
                    latitude = item.latitude,
                    longitude = item.longitude,
                    accuracyMeters = item.accuracyMeters,
                    recordedAt = Instant.ofEpochMilli(minOf(item.recordedAt, now())).toString(),
                )
            )
            dao.markSynced(item.id)
            count++
        }
        return count
    }

    /** Storico remoto di un oggetto (anche registrato da altri membri), su richiesta. */
    suspend fun refresh(objectId: String) {
        api.locationsForObject(objectId).forEach { row ->
            dao.insertIfAbsent(
                LocationHistoryEntity(
                    id = row.id,
                    objectId = row.objectId,
                    latitude = row.latitude,
                    longitude = row.longitude,
                    accuracyMeters = row.accuracyMeters,
                    recordedAt = SyncMerge.parseEpochMillis(row.recordedAt) ?: now(),
                    source = row.source,
                    syncStatus = PhotoSyncStatus.SYNCED.wire,
                )
            )
        }
    }
}
