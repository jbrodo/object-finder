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
import com.google.maps.android.compose.rememberUpdatedMarkerState
import it.objectfinder.app.data.local.ObjectEntity
import it.objectfinder.app.data.local.isDefaultCategory
import it.objectfinder.app.ui.objects.PlaceFormat
import it.objectfinder.app.ui.objects.navigateTo
import it.objectfinder.app.data.Geo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

fun List<ObjectEntity>.toMarkers(): List<MapMarker> = mapNotNull { item ->
    val lat = item.latitude ?: return@mapNotNull null
    val lon = item.longitude ?: return@mapNotNull null
    // Sottotitolo utile per riconoscerlo: il "dove" scritto, altrimenti la categoria se non è quella predefinita.
    val subtitle = item.place.ifBlank { item.category.takeUnless(::isDefaultCategory).orEmpty() }
    MapMarker(item.id, item.name, subtitle, lat, lon)
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
    val groups = remember(markers) { groupMarkers(markers) }
    val withoutLocation = objects.size - markers.size
    var selectedKey by remember { mutableStateOf<String?>(null) }
    val selected = groups.firstOrNull { it.key == selectedKey }
    BackHandler(enabled = selected != null) { selectedKey = null }
    val pinColors = PinColors(MaterialTheme.colorScheme.primary.toArgb(), MaterialTheme.colorScheme.onPrimary.toArgb())

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
        // Il selettore compare solo se c'è davvero una scelta (build con API key Google).
        if (MapEngine.available.size > 1) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MapEngine.available.forEach { option ->
                    FilterChip(
                        selected = engine == option,
                        onClick = { onEngineChange(option) },
                        label = { Text(option.label) },
                    )
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            when (engine) {
                MapEngine.GOOGLE_MAPS ->
                    GoogleObjectsMap(groups, focus, hasPermission, pinColors, { selectedKey = it.key }, Modifier.fillMaxSize())
                MapEngine.OPEN_STREET_MAP ->
                    OsmObjectsMap(groups, focus, hasPermission, pinColors, { selectedKey = it.key }, Modifier.fillMaxSize())
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
            if (selected != null) {
                SelectedGroupPanel(
                    group = selected,
                    myLocation = myLocation,
                    onOpen = onOpenObject,
                    onDismiss = { selectedKey = null },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                )
            } else if (markers.isEmpty() || withoutLocation > 0) {
                Surface(
                    tonalElevation = 3.dp,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                ) {
                    Text(
                        if (markers.isEmpty()) "Nessun oggetto con posizione GPS"
                        else if (withoutLocation == 1) "1 oggetto senza posizione non è mostrato"
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
    groups: List<MarkerGroup>,
    focus: MapFocus?,
    showMyLocation: Boolean,
    colors: PinColors,
    onSelect: (MarkerGroup) -> Unit,
    modifier: Modifier,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current.density
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
        groups.forEach { group ->
            key(group.key) {
                val icon = remember(group.label, colors) {
                    BitmapDescriptorFactory.fromBitmap(labelPinBitmap(group.label, colors.background, colors.foreground, density))
                }
                Marker(
                    state = rememberUpdatedMarkerState(position = LatLng(group.latitude, group.longitude)),
                    title = group.label,
                    icon = icon,
                    anchor = Offset(0.5f, 1f),
                    onClick = { onSelect(group); true },
                )
            }
        }
    }
}

@Composable
private fun OsmObjectsMap(
    groups: List<MarkerGroup>,
    focus: MapFocus?,
    showMyLocation: Boolean,
    colors: PinColors,
    onSelect: (MarkerGroup) -> Unit,
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
            val density = map.resources.displayMetrics.density
            groups.forEach { group ->
                val marker = org.osmdroid.views.overlay.Marker(map).apply {
                    position = GeoPoint(group.latitude, group.longitude)
                    title = group.label
                    icon = android.graphics.drawable.BitmapDrawable(
                        map.resources,
                        labelPinBitmap(group.label, colors.background, colors.foreground, density),
                    )
                    setAnchor(org.osmdroid.views.overlay.Marker.ANCHOR_CENTER, org.osmdroid.views.overlay.Marker.ANCHOR_BOTTOM)
                    setOnMarkerClickListener { _, _ -> onSelect(group); true }
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

data class PinColors(val background: Int, val foreground: Int)

/** Riquadro dal basso per il segnaposto toccato: chi c'è in quel punto, aprirlo o andarci. */
@Composable
private fun SelectedGroupPanel(
    group: MarkerGroup,
    myLocation: DeviceLocation?,
    onOpen: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(top = 12.dp)) {
                    Text(
                        if (group.items.size == 1) "In questo punto" else "${group.items.size} oggetti in questo punto",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics { heading() },
                    )
                    myLocation?.let {
                        Text(
                            PlaceFormat.distanceFromYou(
                                Geo.distanceMeters(it.latitude, it.longitude, group.latitude, group.longitude)
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Chiudi") }
            }
            Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()).padding(end = 12.dp)) {
                group.items.forEachIndexed { index, item ->
                    if (index > 0) HorizontalDivider()
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onOpen(item.id) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (item.snippet.isNotBlank()) Text(
                                item.snippet,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Apri ${item.title}")
                    }
                }
            }
            Button(
                onClick = { navigateTo(context, group.latitude, group.longitude) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, end = 12.dp),
            ) {
                Icon(Icons.Default.Directions, contentDescription = null, Modifier.size(18.dp))
                Text("  Portami lì")
            }
        }
    }
}
