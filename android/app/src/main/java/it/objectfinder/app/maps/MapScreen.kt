package it.objectfinder.app.maps

import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import it.objectfinder.app.ui.common.locationUnavailable
import it.objectfinder.app.ui.common.LocalAppMessages
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableDoubleStateOf
import android.content.Context
import android.view.ViewGroup
import androidx.compose.foundation.layout.widthIn
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
    MapMarker(
        item.id, item.name, subtitle, lat, lon,
        accuracyMeters = item.accuracyMeters.takeIf { !item.isManualLocation },
        approximate = item.isApproximate,
        manual = item.isManualLocation,
    )
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
    // Raggruppamento per distanza sullo schermo: dipende dallo zoom (a mezzi livelli).
    var zoom by remember { mutableDoubleStateOf(15.0) }
    val zoomStep = MapScale.step(zoom)
    val refLatitude = remember(markers) { markers.map { it.latitude }.average().takeIf { !it.isNaN() } ?: 45.0 }
    val groups = remember(markers, zoomStep) {
        groupMarkers(markers, MapScale.clusterRadiusMeters(refLatitude, zoomStep))
    }
    // Pannello aperto (gruppo o singolo oggetto) e gruppo aperto a ventaglio.
    var selected by remember { mutableStateOf<MarkerGroup?>(null) }
    var expandedKey by remember { mutableStateOf<String?>(null) }
    val expanded = groups.firstOrNull { it.key == expandedKey && it.items.size > 1 }
    val legs = remember(expanded, zoomStep) { expanded?.let { spiderLegs(it, zoomStep) }.orEmpty() }
    // Cambiando zoom i gruppi cambiano: il ventaglio si richiude.
    LaunchedEffect(zoomStep) { expandedKey = null }
    BackHandler(enabled = selected != null || expanded != null) { selected = null; expandedKey = null }
    val pinColors = PinColors(
        MaterialTheme.colorScheme.primary.toArgb(),
        MaterialTheme.colorScheme.onPrimary.toArgb(),
        MaterialTheme.colorScheme.surface.toArgb(),
    )

    val context = LocalContext.current
    val messages = LocalAppMessages.current
    val scope = rememberCoroutineScope()
    val locationProvider = remember(context) { LocationProvider(context) }
    val permission = rememberLocationPermissionRequester()
    var hasPermission by remember { mutableStateOf(locationProvider.hasPermission()) }
    var myLocation by remember { mutableStateOf<DeviceLocation?>(null) }
    var locating by remember { mutableStateOf(false) }
    var focus by remember { mutableStateOf<MapFocus?>(null) }

    // Dopo un gesto dell'utente sulla mappa un fix arrivato in ritardo non deve più spostarla.
    var userMovedMap by remember { mutableStateOf(false) }

    fun centerOnMe() {
        if (locating) return
        locating = true
        userMovedMap = false
        scope.launch {
            // Centraggio immediato sull'ultima posizione nota, poi affinamento con un fix fresco.
            val quick = locationProvider.lastKnownLocation()?.also {
                myLocation = it
                focus = MapFocus.Point(it.latitude, it.longitude)
            }
            val location = locationProvider.currentLocation()
            locating = false
            if (location != null) {
                myLocation = location
                val drift = quick?.let { Geo.distanceMeters(it.latitude, it.longitude, location.latitude, location.longitude) }
                // Si ricentra solo se serve davvero (niente posizione rapida o spostamento sensibile)
                // e se nel frattempo l'utente non ha già mosso la mappa.
                if (!userMovedMap && (drift == null || drift > 30)) {
                    focus = MapFocus.Point(location.latitude, location.longitude)
                }
            } else if (myLocation == null) {
                messages.locationUnavailable(context)
            }
        }
    }

    // All'apertura: centra sul telefono se il permesso è già concesso (nessuna richiesta invasiva).
    LaunchedEffect(Unit) { if (hasPermission) centerOnMe() }
    /**
     * Tocco su un segnaposto: un oggetto → riquadro; un gruppo che zoomando si separa → zoom sul
     * gruppo; oggetti nello stesso punto → ventaglio sulla mappa ed elenco nel riquadro.
     */
    fun onGroupTap(group: MarkerGroup) {
        userMovedMap = true
        when {
            group.items.size == 1 -> { selected = group; expandedKey = null }
            group.canSplitByZooming(zoom) -> { selected = null; focus = MapFocus.Fit(group.items) }
            else -> {
                selected = group
                expandedKey = group.key
                // Il riquadro occupa la parte bassa: il ventaglio va portato sopra (≈140 dp più in alto).
                val shift = 140 * MapScale.metersPerDp(group.latitude, zoom) / 111_320.0
                focus = MapFocus.Point(group.latitude - shift, group.longitude, zoom.toFloat())
            }
        }
    }

    fun onLegTap(leg: SpiderLeg) {
        selected = MarkerGroup(leg.item.latitude, leg.item.longitude, listOf(leg.item))
    }

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
                MapEngine.GOOGLE_MAPS -> GoogleObjectsMap(
                    groups.filter { it.key != expanded?.key }, expanded, legs, focus, hasPermission, pinColors, selected,
                    ::onGroupTap, ::onLegTap, { if (it != zoom) { zoom = it; if (!locating) userMovedMap = true } }, Modifier.fillMaxSize(),
                )
                MapEngine.OPEN_STREET_MAP -> OsmObjectsMap(
                    groups.filter { it.key != expanded?.key }, expanded, legs, focus, hasPermission, pinColors, selected,
                    ::onGroupTap, ::onLegTap, { if (it != zoom) { zoom = it; if (!locating) userMovedMap = true } }, Modifier.fillMaxSize(),
                )
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
            val panel = selected
            if (panel != null) {
                SelectedGroupPanel(
                    group = panel,
                    myLocation = myLocation,
                    onOpen = onOpenObject,
                    onDismiss = { selected = null; expandedKey = null },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).widthIn(max = 560.dp),
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
    expanded: MarkerGroup?,
    legs: List<SpiderLeg>,
    focus: MapFocus?,
    showMyLocation: Boolean,
    colors: PinColors,
    selected: MarkerGroup?,
    onSelect: (MarkerGroup) -> Unit,
    onLegTap: (SpiderLeg) -> Unit,
    onZoom: (Double) -> Unit,
    modifier: Modifier,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val camera = rememberCameraPositionState()
    LaunchedEffect(camera) { snapshotFlow { camera.position.zoom }.collect { onZoom(it.toDouble()) } }
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
                val icon = remember(group.label, group.approximate, colors) {
                    BitmapDescriptorFactory.fromBitmap(
                        labelPinBitmap(
                            group.label, colors.background, colors.foreground, density,
                            outlined = group.approximate, surface = colors.surface,
                        )
                    )
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
        // Ventaglio: linee dal punto vero a ogni oggetto, poi i segnaposti.
        expanded?.let { group ->
            legs.forEach { leg ->
                key("leg-${leg.item.id}") {
                    com.google.maps.android.compose.Polyline(
                        points = listOf(LatLng(group.latitude, group.longitude), LatLng(leg.latitude, leg.longitude)),
                        color = androidx.compose.ui.graphics.Color(colors.background),
                        width = 2f * density,
                    )
                    val icon = remember(leg.item.title, leg.item.approximate, colors) {
                        BitmapDescriptorFactory.fromBitmap(
                            labelPinBitmap(
                                leg.item.title, colors.background, colors.foreground, density,
                                outlined = leg.item.approximate, surface = colors.surface,
                            )
                        )
                    }
                    Marker(
                        state = rememberUpdatedMarkerState(position = LatLng(leg.latitude, leg.longitude)),
                        title = leg.item.title,
                        icon = icon,
                        anchor = Offset(0.5f, 1f),
                        onClick = { onLegTap(leg); true },
                    )
                }
            }
            com.google.maps.android.compose.Circle(
                center = LatLng(group.latitude, group.longitude),
                radius = 1.5 * MapScale.metersPerDp(group.latitude, camera.position.zoom.toDouble()) * 4,
                fillColor = androidx.compose.ui.graphics.Color(colors.background),
                strokeColor = androidx.compose.ui.graphics.Color(colors.surface),
                strokeWidth = 2f * density,
            )
        }
        // Area in cui il GPS colloca il punto toccato.
        selected?.uncertaintyMeters?.let { radius ->
            com.google.maps.android.compose.Circle(
                center = LatLng(selected.latitude, selected.longitude),
                radius = radius.toDouble(),
                fillColor = androidx.compose.ui.graphics.Color(colors.background).copy(alpha = 0.12f),
                strokeColor = androidx.compose.ui.graphics.Color(colors.background).copy(alpha = 0.6f),
                strokeWidth = 3f,
            )
        }
    }
}

@Composable
private fun OsmObjectsMap(
    groups: List<MarkerGroup>,
    expanded: MarkerGroup?,
    legs: List<SpiderLeg>,
    focus: MapFocus?,
    showMyLocation: Boolean,
    colors: PinColors,
    selected: MarkerGroup?,
    onSelect: (MarkerGroup) -> Unit,
    onLegTap: (SpiderLeg) -> Unit,
    onZoom: (Double) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember {
        createOsmMapView(context).apply {
            addMapListener(object : org.osmdroid.events.MapListener {
                override fun onScroll(event: org.osmdroid.events.ScrollEvent?) = false
                override fun onZoom(event: org.osmdroid.events.ZoomEvent?): Boolean {
                    onZoom(zoomLevelDouble); return false
                }
            })
        }
    }
    val myLocationOverlay = remember(mapView) { MyLocationNewOverlay(GpsMyLocationProvider(context), mapView) }
    // Segnaposti come bitmap: ricreati solo se cambiano etichetta, stile o colori (non a ogni zoom).
    val pinCache = remember { HashMap<String, android.graphics.Bitmap>() }
    fun pin(label: String, approximate: Boolean, density: Float) = pinCache.getOrPut("$label|$approximate|$colors") {
        labelPinBitmap(label, colors.background, colors.foreground, density, outlined = approximate, surface = colors.surface)
    }

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
            // Area in cui il GPS colloca il punto toccato (sotto i segnaposti).
            selected?.uncertaintyMeters?.let { radius ->
                map.overlays.add(org.osmdroid.views.overlay.Polygon(map).apply {
                    points = org.osmdroid.views.overlay.Polygon.pointsAsCircle(
                        GeoPoint(selected.latitude, selected.longitude), radius.toDouble(),
                    )
                    fillPaint.color = androidx.core.graphics.ColorUtils.setAlphaComponent(colors.background, 31)
                    outlinePaint.color = androidx.core.graphics.ColorUtils.setAlphaComponent(colors.background, 153)
                    outlinePaint.strokeWidth = 3f
                    setOnClickListener { _, _, _ -> false }
                })
            }
            val density = map.resources.displayMetrics.density
            // Ventaglio: linee dal punto vero, segnaposti spostati solo visivamente, punto vero sopra.
            expanded?.let { group ->
                val center = GeoPoint(group.latitude, group.longitude)
                legs.forEach { leg ->
                    map.overlays.add(org.osmdroid.views.overlay.Polyline(map).apply {
                        setPoints(listOf(center, GeoPoint(leg.latitude, leg.longitude)))
                        outlinePaint.color = colors.background
                        outlinePaint.strokeWidth = 2f * density
                        setOnClickListener { _, _, _ -> false }
                    })
                }
                legs.forEach { leg ->
                    map.overlays.add(org.osmdroid.views.overlay.Marker(map).apply {
                        position = GeoPoint(leg.latitude, leg.longitude)
                        title = leg.item.title
                        icon = android.graphics.drawable.BitmapDrawable(map.resources, pin(leg.item.title, leg.item.approximate, density))
                        setAnchor(org.osmdroid.views.overlay.Marker.ANCHOR_CENTER, org.osmdroid.views.overlay.Marker.ANCHOR_BOTTOM)
                        setOnMarkerClickListener { _, _ -> onLegTap(leg); true }
                    })
                }
                map.overlays.add(org.osmdroid.views.overlay.Marker(map).apply {
                    position = center
                    icon = android.graphics.drawable.BitmapDrawable(map.resources, centerDotBitmap(colors, density))
                    setAnchor(org.osmdroid.views.overlay.Marker.ANCHOR_CENTER, org.osmdroid.views.overlay.Marker.ANCHOR_CENTER)
                    setOnMarkerClickListener { _, _ -> true }
                })
            }
            groups.forEach { group ->
                val marker = org.osmdroid.views.overlay.Marker(map).apply {
                    position = GeoPoint(group.latitude, group.longitude)
                    title = group.label
                    icon = android.graphics.drawable.BitmapDrawable(map.resources, pin(group.label, group.approximate, density))
                    setAnchor(org.osmdroid.views.overlay.Marker.ANCHOR_CENTER, org.osmdroid.views.overlay.Marker.ANCHOR_BOTTOM)
                    setOnMarkerClickListener { _, _ -> onSelect(group); true }
                }
                map.overlays.add(marker)
            }
            map.invalidate()
        },
    )
}

internal fun createOsmMapView(context: Context): MapView {
    Configuration.getInstance().apply {
        load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        // User-Agent identificativo richiesto dalla tile usage policy di OSM.
        userAgentValue = context.packageName
    }
    return MapView(context).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setTileSource(TileSourceFactory.MAPNIK)
        // Tile in dp (come Google Maps): stessa scala per il raggruppamento e testo leggibile.
        isTilesScaledToDpi = true
        setMultiTouchControls(true)
        controller.setZoom(15.0)
    }
}

/** Dove portare la camera: un punto (es. posizione del telefono) o l'inquadratura degli oggetti. */
sealed interface MapFocus {
    // Classi normali (uguaglianza per identità): ogni richiesta muove la camera, anche se uguale
    // alla precedente (es. toccare il gruppo di tutti gli oggetti già inquadrati all'apertura).
    class Point(val latitude: Double, val longitude: Double, val zoom: Float = 16f) : MapFocus
    class Fit(val markers: List<MapMarker>) : MapFocus
}

data class PinColors(val background: Int, val foreground: Int, val surface: Int)

/** Punto vero di un gruppo aperto a ventaglio. */
internal fun centerDotBitmap(colors: PinColors, density: Float): android.graphics.Bitmap {
    val size = (14 * density).toInt()
    val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    paint.color = colors.surface
    canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
    paint.color = colors.background
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - 2.5f * density, paint)
    return bitmap
}

/** Riga del riquadro: il "dove" scritto e quanto è affidabile il punto. */
internal fun markerDetail(item: MapMarker): String = listOfNotNull(
    item.snippet.ifBlank { null },
    when {
        item.manual -> "indicata a mano"
        item.approximate -> "approssimata ±${item.accuracyMeters!!.toInt()} m"
        else -> null
    },
).joinToString(" · ")

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
    val messages = LocalAppMessages.current
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
                            val detail = markerDetail(item)
                            if (detail.isNotBlank()) Text(
                                detail,
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
                onClick = { if (!navigateTo(context, group.latitude, group.longitude)) messages.show("Nessuna app mappe installata") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, end = 12.dp),
            ) {
                Icon(Icons.Default.Directions, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("Portami lì")
            }
        }
    }
}
