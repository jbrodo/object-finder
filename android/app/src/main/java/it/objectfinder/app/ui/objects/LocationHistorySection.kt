package it.objectfinder.app.ui.objects

import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import it.objectfinder.app.ui.common.LocalAppMessages
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import it.objectfinder.app.data.Geo
import it.objectfinder.app.data.local.LocationHistoryEntity
import java.util.Locale

/** Dove è stato l'oggetto e quando; ogni voce si apre nell'app mappe. */
@Composable
fun LocationHistorySection(history: List<LocationHistoryEntity>) {
    if (history.isEmpty()) return
    val context = LocalContext.current
    val messages = LocalAppMessages.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    val shown = if (expanded) history else history.take(3)
    

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.History, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("Storico posizioni", style = MaterialTheme.typography.titleSmall)
            }
            shown.forEachIndexed { index, item ->
                val previous = history.getOrNull(index + 1) // le voci mostrate sono un prefisso dello storico
                val moved = previous?.let {
                    Geo.distanceMeters(it.latitude, it.longitude, item.latitude, item.longitude)
                }
                Row(
                    Modifier.fillMaxWidth().clickable { if (!openInMaps(context, item.latitude, item.longitude)) messages.show("Nessuna app mappe installata") },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Place,
                        contentDescription = null,
                        Modifier.size(16.dp),
                        tint = if (index == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    )
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(
                            PlaceFormat.dateTime(item.recordedAt) + if (index == 0) " · attuale" else "",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            PlaceFormat.coordinates(item.latitude, item.longitude, item.accuracyMeters) +
                                (moved?.let { " · spostato di ${Geo.formatDistance(it)}" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (history.size > 3) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Mostra meno" else "Mostra tutto (${history.size})")
                }
            }
        }
    }
}

/**
 * Navigazione verso l'oggetto (a piedi) con Google Maps; altrimenti qualsiasi app mappe.
 * false se nessuna app può aprirla: il messaggio lo mostra chi chiama.
 */
fun navigateTo(context: Context, latitude: Double, longitude: Double): Boolean {
    val coords = String.format(Locale.ROOT, "%.6f,%.6f", latitude, longitude)
    val directions = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$coords&travelmode=walking"),
    )
    try {
        context.startActivity(directions)
        return true
    } catch (_: ActivityNotFoundException) {
        return openInMaps(context, latitude, longitude)
    }
}

/** Apre il punto nell'app mappe; false se non ce n'è nessuna. */
fun openInMaps(context: Context, latitude: Double, longitude: Double): Boolean {
    val coords = String.format(Locale.ROOT, "%.6f,%.6f", latitude, longitude)
    return try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$coords?q=$coords")))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
