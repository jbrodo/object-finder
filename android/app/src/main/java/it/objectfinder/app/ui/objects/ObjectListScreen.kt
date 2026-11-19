package it.objectfinder.app.ui.objects

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import android.widget.Toast
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import it.objectfinder.app.data.Geo
import it.objectfinder.app.location.LocationProvider
import it.objectfinder.app.ui.common.rememberLocationPermissionRequester
import kotlinx.coroutines.launch
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import coil.compose.AsyncImage
import java.io.File
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.objectfinder.app.data.local.ObjectEntity
import it.objectfinder.app.data.local.ObjectStatus
import java.util.Locale

@Composable
fun ObjectListScreen(
    viewModel: ObjectListViewModel,
    onOpenObject: (String) -> Unit,
    onCreateObject: () -> Unit,
    modifier: Modifier = Modifier,
    canCreate: Boolean = true,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val permission = rememberLocationPermissionRequester()
    var locating by remember { mutableStateOf(false) }

    fun toggleNearby() {
        if (state.origin != null) { viewModel.setOrigin(null); return }
        permission.withPermission {
            locating = true
            scope.launch {
                val provider = LocationProvider(context)
                // Ordinamento immediato con l'ultima posizione nota, poi affinato con un fix fresco.
                val quick = provider.lastKnownLocation()?.also(viewModel::setOrigin)
                val fresh = provider.currentLocation()
                locating = false
                when {
                    fresh != null -> viewModel.setOrigin(fresh)
                    quick == null -> Toast.makeText(
                        context, "Posizione non disponibile: verifica che il GPS sia attivo", Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            placeholder = { Text("Cerca nome, note o codice") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ObjectFilter.entries.forEach { option ->
                FilterChip(
                    selected = state.filter == option,
                    onClick = { viewModel.setFilter(option) },
                    label = { Text(option.label) },
                )
            }
            FilterChip(
                selected = state.origin != null,
                onClick = ::toggleNearby,
                leadingIcon = {
                    if (locating) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.NearMe, contentDescription = null, Modifier.size(16.dp))
                },
                label = { Text("Vicini") },
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.loading -> Unit
                state.totalInProject == 0 -> EmptyState(
                    title = "Nessun oggetto registrato",
                    body = "Registra il primo oggetto: ne salveremo la posizione GPS per ritrovarlo sulla mappa.",
                )
                state.items.isEmpty() -> EmptyState(
                    title = "Nessun risultato",
                    body = "Nessun oggetto corrisponde a ricerca e filtro selezionati.",
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.items, key = { it.id }) { item ->
                        ObjectCard(
                            item,
                            cover = state.covers[item.id],
                            distance = state.distanceTo(item),
                            onClick = { onOpenObject(item.id) },
                        )
                    }
                }
            }
            if (canCreate) ExtendedFloatingActionButton(
                onClick = onCreateObject,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Registra oggetto") },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }
}

@Composable
private fun ObjectCard(item: ObjectEntity, cover: String?, distance: Double?, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
      Row(Modifier.padding(start = if (cover != null) 12.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
        if (cover != null) {
            AsyncImage(
                model = File(cover),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)),
            )
        }
        Column(Modifier.weight(1f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    when {
                        item.isConflict -> Icons.Default.Warning
                        item.isPending -> Icons.Default.CloudUpload
                        else -> Icons.Default.CloudDone
                    },
                    contentDescription = when {
                        item.isConflict -> "Conflitto da risolvere"
                        item.isPending -> "Da sincronizzare"
                        else -> "Sincronizzato"
                    },
                    tint = if (item.isConflict) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                "${item.category} · ${item.statusEnum.label}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (item.statusEnum == ObjectStatus.ARCHIVED) MaterialTheme.colorScheme.outline
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (item.hasLocation) Icons.Default.LocationOn else Icons.Default.LocationOff,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    distance?.let {
                        // Sotto i 10 m la differenza è dentro l'incertezza del GPS.
                        if (it < 10) "a meno di 10 m da te" else "a ${Geo.formatDistance(it)} da te"
                    } ?: formatLocation(item),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (distance != null) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Unspecified,
                )
            }
            if (item.notes.isNotBlank()) {
                Text(item.notes, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
      }
    }
}

@Composable
private fun EmptyState(title: String, body: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.size(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

internal fun formatLocation(item: ObjectEntity): String {
    val lat = item.latitude
    val lon = item.longitude
    if (lat == null || lon == null) return "Posizione non acquisita"
    val accuracy = item.accuracyMeters?.let { " · ±${it.toInt()} m" } ?: ""
    return String.format(Locale.ROOT, "%.6f, %.6f", lat, lon) + accuracy
}
