package it.objectfinder.app.maps

/**
 * Astrazione del provider cartografico.
 * Le implementazioni Google Maps e OpenStreetMap possono essere selezionate
 * senza modificare il dominio o il repository.
 */
enum class MapEngine { GOOGLE_MAPS, OPEN_STREET_MAP }

data class MapMarker(
    val id: String,
    val title: String,
    val latitude: Double,
    val longitude: Double
)

interface MapProvider {
    val engine: MapEngine
    fun markers(): List<MapMarker>
}
