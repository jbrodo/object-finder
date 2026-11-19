package it.objectfinder.app.maps

import android.content.Context
import android.view.ViewGroup
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
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (engine) {
                MapEngine.GOOGLE_MAPS -> GoogleObjectsMap(markers, onOpenObject, Modifier.fillMaxSize())
                MapEngine.OPEN_STREET_MAP -> OsmObjectsMap(markers, onOpenObject, Modifier.fillMaxSize())
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
private fun GoogleObjectsMap(markers: List<MapMarker>, onOpen: (String) -> Unit, modifier: Modifier) {
    val camera = rememberCameraPositionState()
    LaunchedEffect(markers.map { it.id }) {
        when (markers.size) {
            0 -> Unit
            1 -> camera.move(CameraUpdateFactory.newLatLngZoom(LatLng(markers[0].latitude, markers[0].longitude), 16f))
            else -> {
                val bounds = LatLngBounds.builder().apply {
                    markers.forEach { include(LatLng(it.latitude, it.longitude)) }
                }.build()
                runCatching { camera.move(CameraUpdateFactory.newLatLngBounds(bounds, 120)) }
            }
        }
    }
    GoogleMap(
        modifier = modifier,
        cameraPositionState = camera,
        uiSettings = MapUiSettings(zoomControlsEnabled = true, mapToolbarEnabled = false),
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
private fun OsmObjectsMap(markers: List<MapMarker>, onOpen: (String) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember { createOsmMapView(context) }

    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { mapView },
        update = { map ->
            map.overlays.clear()
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
            when (markers.size) {
                0 -> Unit
                1 -> map.controller.setCenter(GeoPoint(markers[0].latitude, markers[0].longitude))
                else -> map.post {
                    val box = BoundingBox.fromGeoPoints(markers.map { GeoPoint(it.latitude, it.longitude) })
                    runCatching { map.zoomToBoundingBox(box.increaseByScale(1.3f), false) }
                }
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
