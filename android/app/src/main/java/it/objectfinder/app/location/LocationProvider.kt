package it.objectfinder.app.location

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.tasks.await

data class DeviceLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float
)

class LocationProvider(context: Context) {
    private val client = LocationServices.getFusedLocationProviderClient(context)

    @SuppressLint("MissingPermission")
    suspend fun currentLocation(): DeviceLocation? {
        val location = client.lastLocation.await() ?: return null
        return DeviceLocation(location.latitude, location.longitude, location.accuracy)
    }
}
