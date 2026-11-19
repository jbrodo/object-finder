package it.objectfinder.app.maps

import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.rememberCameraPositionState
import it.objectfinder.app.data.ObjectEntity
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

@Composable
fun ObjectMapScreen(objects: List<ObjectEntity>) {
    var engine by remember { mutableStateOf(MapEngine.GOOGLE_MAPS) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = engine == MapEngine.GOOGLE_MAPS,
                onClick = { engine = MapEngine.GOOGLE_MAPS },
                label = { Text("Google Maps") }
            )
            FilterChip(
                selected = engine == MapEngine.OPEN_STREET_MAP,
                onClick = { engine = MapEngine.OPEN_STREET_MAP },
                label = { Text("OpenStreetMap") }
            )
        }
        when (engine) {
            MapEngine.GOOGLE_MAPS -> GoogleObjectsMap(objects, Modifier.weight(1f))
            MapEngine.OPEN_STREET_MAP -> OsmObjectsMap(objects, Modifier.weight(1f))
        }
    }
}

@Composable
private fun GoogleObjectsMap(objects: List<ObjectEntity>, modifier: Modifier) {
    val located = objects.filter { it.latitude != null && it.longitude != null }
    val first = located.firstOrNull()
    val camera = rememberCameraPositionState()
    LaunchedEffect(first?.id) {
        if (first != null) {
            camera.animate(CameraUpdateFactory.newLatLngZoom(
                LatLng(first.latitude!!, first.longitude!!), 16f
            ))
        }
    }
    GoogleMap(modifier = modifier, cameraPositionState = camera) {
        located.forEach { item ->
            Marker(
                state = com.google.maps.android.compose.MarkerState(
                    position = LatLng(item.latitude!!, item.longitude!!)
                ),
                title = item.name,
                snippet = item.category
            )
        }
    }
}

@Composable
private fun OsmObjectsMap(objects: List<ObjectEntity>, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            Configuration.getInstance().userAgentValue = context.packageName
            MapView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                controller.setZoom(15.0)
            }
        },
        update = { map ->
            map.overlays.clear()
            objects.filter { it.latitude != null && it.longitude != null }.forEach { item ->
                val marker = org.osmdroid.views.overlay.Marker(map)
                marker.position = GeoPoint(item.latitude!!, item.longitude!!)
                marker.title = item.name
                marker.snippet = item.category
                map.overlays.add(marker)
            }
            val first = objects.firstOrNull { it.latitude != null && it.longitude != null }
            if (first != null) map.controller.setCenter(GeoPoint(first.latitude!!, first.longitude!!))
            map.invalidate()
        }
    )
}
