package it.objectfinder.app.maps

import it.objectfinder.app.BuildConfig

/**
 * Provider cartografici intercambiabili: il dominio espone solo coordinate/[MapMarker].
 * MapLibre potrà aggiungersi come terzo motore senza toccare repository o UI degli oggetti.
 */
enum class MapEngine(val label: String) {
    GOOGLE_MAPS("Google Maps"),
    OPEN_STREET_MAP("OpenStreetMap");

    val isAvailable: Boolean
        get() = this != GOOGLE_MAPS || BuildConfig.HAS_GOOGLE_MAPS_KEY

    companion object {
        val available: List<MapEngine> get() = entries.filter { it.isAvailable }

        /** Se la preferenza non è utilizzabile (es. APK senza API key Google) ripiega su OSM. */
        fun resolve(preferred: MapEngine): MapEngine = if (preferred.isAvailable) preferred else OPEN_STREET_MAP
    }
}

data class MapMarker(
    val id: String,
    val title: String,
    val snippet: String,
    val latitude: Double,
    val longitude: Double,
    /** Incertezza del GPS (null se punto indicato a mano o sconosciuta). */
    val accuracyMeters: Float? = null,
    val approximate: Boolean = false,
    val manual: Boolean = false,
)
