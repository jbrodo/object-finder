package it.objectfinder.app.maps

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.rememberCameraPositionState
import it.objectfinder.app.location.LocationProvider
import it.objectfinder.app.ui.objects.PlaceFormat
import kotlinx.coroutines.launch
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Polygon

/** Punto di partenza del selettore: posizione nota dell'oggetto (con la sua incertezza) o del telefono. */
data class PickerStart(val latitude: Double, val longitude: Double, val accuracyMeters: Float? = null)

/**
 * Correzione manuale della posizione: la mappa si sposta sotto un mirino fisso al centro (più
 * preciso che trascinare un segnaposto, che il dito coprirebbe). Il cerchio mostra l'incertezza
 * del GPS di partenza, per capire dove cercare.
 */
@Composable
fun LocationPickerDialog(
    start: PickerStart?,
    engine: MapEngine,
    onConfirm: (latitude: Double, longitude: Double) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val provider = remember(context) { LocationProvider(context) }
    // Senza nessun punto noto si parte dall'Italia intera; il pulsante "mia posizione" avvicina.
    val initial = start ?: PickerStart(42.5, 12.5)
    var center by remember { mutableStateOf(initial.latitude to initial.longitude) }
    var recenter by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var locating by remember { mutableStateOf(false) }
    val zoom = if (start == null) 6f else 19f

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize()) {
            when (MapEngine.resolve(engine)) {
                MapEngine.GOOGLE_MAPS -> GooglePicker(initial, zoom, recenter, onCenter = { center = it })
                MapEngine.OPEN_STREET_MAP -> OsmPicker(initial, zoom, recenter, onCenter = { center = it })
            }
            Crosshair(Modifier.align(Alignment.Center))

            SmallFloatingActionButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp),
            ) { Icon(Icons.Default.Close, contentDescription = "Annulla") }
            SmallFloatingActionButton(
                onClick = {
                    if (locating) return@SmallFloatingActionButton
                    locating = true
                    scope.launch {
                        val here = provider.lastKnownLocation() ?: provider.currentLocation()
                        locating = false
                        here?.let { recenter = it.latitude to it.longitude }
                    }
                },
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp),
            ) {
                if (locating) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.MyLocation, contentDescription = "Vai alla mia posizione")
            }

            Surface(
                tonalElevation = 3.dp,
                shadowElevation = 6.dp,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp).fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Indica il punto esatto", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (start?.accuracyMeters != null)
                            "Sposta la mappa finché il mirino è sull'oggetto. Il cerchio è l'area in cui il GPS lo collocava."
                        else "Sposta la mappa finché il mirino è sull'oggetto.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        PlaceFormat.coordinates(center.first, center.second, null),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Annulla") }
                        Button(onClick = { onConfirm(center.first, center.second) }, modifier = Modifier.weight(1f)) {
                            Text("Conferma qui")
                        }
                    }
                }
            }
        }
    }
}

/** Mirino: anello e croce con alone chiaro, leggibile su qualsiasi sfondo della mappa. */
@Composable
private fun Crosshair(modifier: Modifier) {
    val ink = MaterialTheme.colorScheme.primary
    Canvas(modifier.size(56.dp).semantics { contentDescription = "Mirino: punto scelto" }) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = size.minDimension / 2 - 6.dp.toPx()
        listOf(Color.White to 6.dp.toPx(), ink to 2.5.dp.toPx()).forEach { (color, width) ->
            drawCircle(color, radius = r, center = c, style = Stroke(width))
            drawLine(color, Offset(c.x, 0f), Offset(c.x, c.y - r / 2), width)
            drawLine(color, Offset(c.x, size.height), Offset(c.x, c.y + r / 2), width)
            drawLine(color, Offset(0f, c.y), Offset(c.x - r / 2, c.y), width)
            drawLine(color, Offset(size.width, c.y), Offset(c.x + r / 2, c.y), width)
        }
        drawCircle(ink, radius = 3.dp.toPx(), center = c)
    }
}

@Composable
private fun GooglePicker(
    start: PickerStart,
    zoom: Float,
    recenter: Pair<Double, Double>?,
    onCenter: (Pair<Double, Double>) -> Unit,
) {
    val camera = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(start.latitude, start.longitude), zoom)
    }
    LaunchedEffect(camera) {
        snapshotFlow { camera.position.target }.collect { onCenter(it.latitude to it.longitude) }
    }
    LaunchedEffect(recenter) {
        recenter?.let { camera.animate(CameraUpdateFactory.newLatLngZoom(LatLng(it.first, it.second), 19f)) }
    }
    val tint = MaterialTheme.colorScheme.primary
    GoogleMap(
        modifier = Modifier.fillMaxSize(),
        cameraPositionState = camera,
        uiSettings = MapUiSettings(zoomControlsEnabled = false, mapToolbarEnabled = false, myLocationButtonEnabled = false),
    ) {
        start.accuracyMeters?.let { radius ->
            Circle(
                center = LatLng(start.latitude, start.longitude),
                radius = radius.toDouble(),
                fillColor = tint.copy(alpha = 0.12f),
                strokeColor = tint.copy(alpha = 0.6f),
                strokeWidth = 3f,
            )
        }
    }
}

@Composable
private fun OsmPicker(
    start: PickerStart,
    zoom: Float,
    recenter: Pair<Double, Double>?,
    onCenter: (Pair<Double, Double>) -> Unit,
) {
    val context = LocalContext.current
    val tint = MaterialTheme.colorScheme.primary
    val mapView = remember {
        createOsmMapView(context).apply {
            controller.setZoom(zoom.toDouble())
            controller.setCenter(GeoPoint(start.latitude, start.longitude))
            start.accuracyMeters?.let { radius ->
                overlays.add(Polygon(this).apply {
                    points = Polygon.pointsAsCircle(GeoPoint(start.latitude, start.longitude), radius.toDouble())
                    fillPaint.color = tint.copy(alpha = 0.12f).toArgb()
                    outlinePaint.color = tint.copy(alpha = 0.6f).toArgb()
                    outlinePaint.strokeWidth = 3f
                    setOnClickListener { _, _, _ -> false } // il cerchio non intercetta i tocchi
                })
            }
            addMapListener(object : MapListener {
                override fun onScroll(event: ScrollEvent?): Boolean {
                    onCenter(mapCenter.latitude to mapCenter.longitude); return false
                }
                override fun onZoom(event: ZoomEvent?): Boolean {
                    onCenter(mapCenter.latitude to mapCenter.longitude); return false
                }
            })
        }
    }
    DisposableEffect(mapView) {
        mapView.onResume()
        onDispose { mapView.onPause(); mapView.onDetach() }
    }
    LaunchedEffect(recenter) {
        recenter?.let {
            mapView.controller.setZoom(19.0)
            mapView.controller.animateTo(GeoPoint(it.first, it.second))
        }
    }
    AndroidView(modifier = Modifier.fillMaxSize(), factory = { mapView })
}
