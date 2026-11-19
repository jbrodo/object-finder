package it.objectfinder.app.maps

import android.content.Context
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.google.maps.android.compose.MapProperties
import it.objectfinder.app.location.DeviceLocation
import it.objectfinder.app.location.LocationProvider
import it.objectfinder.app.ui.common.rememberLocationPermissionRequester
import kotlinx.coroutines.launch
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState
import it.objectfinder.app.data.local.ObjectEntity
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

fun List<ObjectEntity>.toMarkers(): List<MapMarker> = mapNotNull { item ->
    val lat = item.latitude ?: return@mapNotNull null
    val lon = item.longitude ?: return@mapNotNull null
    MapMarker(item.id, item.name, "${item.category} · ${item.statusEnum.label}", lat, lon)
}

@Composable
fun ObjectMapScreen(
    objects: List<ObjectEntity>,
    preferredEngine: MapEngine,
    onEngineChange: (MapEngine) -> Unit,
    onOpenObject: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val engine = MapEngine.resolve(preferredEngine)
    val markers = remember(objects) { objects.toMarkers() }
    val withoutLocation = objects.size - markers.size

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val locationProvider = remember(context) { LocationProvider(context) }
    val permission = rememberLocationPermissionRequester()
    var hasPermission by remember { mutableStateOf(locationProvider.hasPermission()) }
    var myLocation by remember { mutableStateOf<DeviceLocation?>(null) }
    var locating by remember { mutableStateOf(false) }
    var focus by remember { mutableStateOf<MapFocus?>(null) }

    fun centerOnMe() {
        if (locating) return
        locating = true
        scope.launch {
            // Centraggio immediato sull'ultima posizione nota, poi affinamento con un fix fresco.
            locationProvider.lastKnownLocation()?.let {
                myLocation = it
                focus = MapFocus.Point(it.latitude, it.longitude)
            }
            val location = locationProvider.currentLocation()
            locating = false
            if (location != null) {
                myLocation = location
                focus = MapFocus.Point(location.latitude, location.longitude)
            } else if (myLocation == null) {
                Toast.makeText(context, "Posizione non disponibile: verifica che il GPS sia attivo", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // All'apertura: centra sul telefono se il permesso è già concesso (nessuna richiesta invasiva).
    LaunchedEffect(Unit) { if (hasPermission) centerOnMe() }
    // Fallback: finché non c'è una posizione del telefono, inquadra gli oggetti.
    LaunchedEffect(markers.map { it.id }) {
        if (focus !is MapFocus.Point && markers.isNotEmpty()) focus = MapFocus.Fit(markers)
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MapEngine.entries.forEach { option ->
                FilterChip(
                    selected = engine == option,
                    enabled = option.isAvailable,
                    onClick = { onEngineChange(option) },
                    label = { Text(option.label) },
                )
            }
        }
        if (!MapEngine.GOOGLE_MAPS.isAvailable) {
            Text(
                "Google Maps non configurato (API key assente): uso OpenStreetMap.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            when (engine) {
                MapEngine.GOOGLE_MAPS ->
                    GoogleObjectsMap(markers, focus, hasPermission, onOpenObject, Modifier.fillMaxSize())
                MapEngine.OPEN_STREET_MAP ->
                    OsmObjectsMap(markers, focus, hasPermission, onOpenObject, Modifier.fillMaxSize())
            }
            SmallFloatingActionButton(
                onClick = {
                    permission.withPermission {
                        hasPermission = true
                        centerOnMe()
                    }
                },
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
            ) {
                if (locating) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.MyLocation, contentDescription = "La mia posizione")
            }
            if (markers.isEmpty() || withoutLocation > 0) {
                Surface(
                    tonalElevation = 3.dp,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                ) {
                    Text(
                        if (markers.isEmpty()) "Nessun oggetto con posizione GPS"
                        else "$withoutLocation oggetti senza posizione non sono mostrati",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun GoogleObjectsMap(
    markers: List<MapMarker>,
    focus: MapFocus?,
    showMyLocation: Boolean,
    onOpen: (String) -> Unit,
    modifier: Modifier,
) {
    val camera = rememberCameraPositionState()
    LaunchedEffect(focus) {
        when (focus) {
            null -> Unit
            is MapFocus.Point -> camera.animate(CameraUpdateFactory.newLatLngZoom(LatLng(focus.latitude, focus.longitude), focus.zoom))
            is MapFocus.Fit -> when (focus.markers.size) {
                1 -> camera.move(
                    CameraUpdateFactory.newLatLngZoom(LatLng(focus.markers[0].latitude, focus.markers[0].longitude), 16f)
                )
                else -> {
                    val bounds = LatLngBounds.builder().apply {
                        focus.markers.forEach { include(LatLng(it.latitude, it.longitude)) }
                    }.build()
                    runCatching { camera.move(CameraUpdateFactory.newLatLngBounds(bounds, 120)) }
                }
            }
        }
    }
    GoogleMap(
        modifier = modifier,
        cameraPositionState = camera,
        properties = MapProperties(isMyLocationEnabled = showMyLocation),
        uiSettings = MapUiSettings(
            zoomControlsEnabled = true,
            mapToolbarEnabled = false,
            myLocationButtonEnabled = false, // c'è il nostro pulsante, uguale per entrambi i motori
        ),
    ) {
        markers.forEach { marker ->
            key(marker.id) {
                Marker(
                    state = rememberMarkerState(position = LatLng(marker.latitude, marker.longitude)),
                    title = marker.title,
                    snippet = "${marker.snippet} · tocca per aprire",
                    onInfoWindowClick = { onOpen(marker.id) },
                )
            }
        }
    }
}

@Composable
private fun OsmObjectsMap(
    markers: List<MapMarker>,
    focus: MapFocus?,
    showMyLocation: Boolean,
    onOpen: (String) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember { createOsmMapView(context) }
    val myLocationOverlay = remember(mapView) { MyLocationNewOverlay(GpsMyLocationProvider(context), mapView) }

    DisposableEffect(lifecycle, mapView, showMyLocation) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    mapView.onResume()
                    if (showMyLocation) myLocationOverlay.enableMyLocation()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    myLocationOverlay.disableMyLocation()
                    mapView.onPause()
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (showMyLocation && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            myLocationOverlay.enableMyLocation()
        }
        onDispose {
            lifecycle.removeObserver(observer)
            myLocationOverlay.disableMyLocation()
        }
    }
    DisposableEffect(mapView) { onDispose { mapView.onDetach() } }

    LaunchedEffect(focus) {
        when (focus) {
            null -> Unit
            is MapFocus.Point -> {
                mapView.controller.setZoom(focus.zoom.toDouble())
                mapView.controller.animateTo(GeoPoint(focus.latitude, focus.longitude))
            }
            is MapFocus.Fit -> when (focus.markers.size) {
                1 -> mapView.controller.setCenter(GeoPoint(focus.markers[0].latitude, focus.markers[0].longitude))
                else -> mapView.post {
                    val box = BoundingBox.fromGeoPoints(focus.markers.map { GeoPoint(it.latitude, it.longitude) })
                    runCatching { mapView.zoomToBoundingBox(box.increaseByScale(1.3f), false) }
                }
            }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { mapView },
        update = { map ->
            map.overlays.clear()
            if (showMyLocation) map.overlays.add(myLocationOverlay)
            markers.forEach { item ->
                val marker = org.osmdroid.views.overlay.Marker(map).apply {
                    position = GeoPoint(item.latitude, item.longitude)
                    title = item.title
                    snippet = item.snippet
                    setOnMarkerClickListener { m, _ ->
                        if (m.isInfoWindowShown) onOpen(item.id) else m.showInfoWindow()
                        true
                    }
                }
                map.overlays.add(marker)
            }
            map.invalidate()
        },
    )
}

private fun createOsmMapView(context: Context): MapView {
    Configuration.getInstance().apply {
        load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        // User-Agent identificativo richiesto dalla tile usage policy di OSM.
        userAgentValue = context.packageName
    }
    return MapView(context).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setTileSource(TileSourceFactory.MAPNIK)
        setMultiTouchControls(true)
        controller.setZoom(15.0)
    }
}

/** Dove portare la camera: un punto (es. posizione del telefono) o l'inquadratura degli oggetti. */
sealed interface MapFocus {
    data class Point(val latitude: Double, val longitude: Double, val zoom: Float = 16f) : MapFocus
    data class Fit(val markers: List<MapMarker>) : MapFocus
}
