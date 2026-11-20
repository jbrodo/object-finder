package it.objectfinder.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

data class DeviceLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
)

class LocationProvider(context: Context) {
    private val appContext = context.applicationContext
    private val client = LocationServices.getFusedLocationProviderClient(appContext)

    fun hasPermission(): Boolean = hasLocationPermission(appContext)

    /** Ultima posizione nota (istantanea, può essere vecchia): utile per un primo centraggio. */
    @SuppressLint("MissingPermission")
    suspend fun lastKnownLocation(): DeviceLocation? {
        if (!hasPermission()) return null
        val location = runCatching { client.lastLocation.await() }.getOrNull() ?: return null
        return DeviceLocation(location.latitude, location.longitude, location.accuracy)
    }

    /**
     * Fix "fresco" ad alta precisione (max 15 s); se non disponibile ripiega sull'ultima
     * posizione nota. Restituisce null se GPS spento o permesso negato.
     */
    /**
     * Fix continui ad alta precisione (circa uno al secondo) finché il chiamante raccoglie: chi
     * raccoglie tiene il migliore e si ferma quando è buono o scade il tempo (vedi [FixPolicy]).
     */
    @SuppressLint("MissingPermission")
    fun fixes(): Flow<DeviceLocation> = callbackFlow {
        if (!hasPermission()) { close(); return@callbackFlow }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000)
            .setMinUpdateIntervalMillis(500)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { trySend(DeviceLocation(it.latitude, it.longitude, it.accuracy)) }
            }
        }
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        awaitClose { client.removeLocationUpdates(callback) }
    }

    @SuppressLint("MissingPermission")
    suspend fun currentLocation(): DeviceLocation? {
        if (!hasPermission()) return null
        val cancellation = CancellationTokenSource()
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMaxUpdateAgeMillis(30_000)
            .setDurationMillis(15_000)
            .build()
        val fresh = runCatching {
            withTimeoutOrNull(16_000) { client.getCurrentLocation(request, cancellation.token).await() }
        }.getOrNull()
        if (fresh == null) cancellation.cancel()
        val location = fresh ?: runCatching { client.lastLocation.await() }.getOrNull() ?: return null
        return DeviceLocation(location.latitude, location.longitude, location.accuracy)
    }
}

/** Regole del rilevamento "migliore entro N secondi" (pure, testabili). */
object FixPolicy {
    /** Sotto questa incertezza il fix è buono: ci si ferma subito. */
    const val GOOD_ACCURACY_METERS = 10f
    const val MAX_WAIT_MILLIS = 15_000L

    fun better(current: DeviceLocation?, candidate: DeviceLocation): DeviceLocation =
        if (current == null || candidate.accuracyMeters < current.accuracyMeters) candidate else current

    fun isGoodEnough(fix: DeviceLocation): Boolean = fix.accuracyMeters <= GOOD_ACCURACY_METERS
}

fun hasLocationPermission(context: Context): Boolean =
    listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
