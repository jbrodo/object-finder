package it.objectfinder.app.ui.objects

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Search
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

    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            placeholder = { Text("Cerca per nome, categoria, note") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ObjectFilter.entries.forEach { option ->
                FilterChip(
                    selected = state.filter == option,
                    onClick = { viewModel.setFilter(option) },
                    label = { Text(option.label) },
                )
            }
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
                        ObjectCard(item, cover = state.covers[item.id], onClick = { onOpenObject(item.id) })
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
private fun ObjectCard(item: ObjectEntity, cover: String?, onClick: () -> Unit) {
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
                    if (item.isPending) Icons.Default.CloudUpload else Icons.Default.CloudDone,
                    contentDescription = if (item.isPending) "Da sincronizzare" else "Sincronizzato",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
                Text(formatLocation(item), style = MaterialTheme.typography.bodySmall)
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
