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

fun hasLocationPermission(context: Context): Boolean =
    listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
