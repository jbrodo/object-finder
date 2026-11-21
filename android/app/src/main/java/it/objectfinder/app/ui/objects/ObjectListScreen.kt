package it.objectfinder.app.ui.objects

import androidx.compose.material3.ButtonDefaults
import it.objectfinder.app.ui.common.locationUnavailable
import it.objectfinder.app.ui.common.LocalAppMessages
import it.objectfinder.app.ui.common.ReadableMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.Place
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import it.objectfinder.app.export.SheetLabel
import it.objectfinder.app.export.LabelSheetLayout
import it.objectfinder.app.export.LabelSheetActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.CardDefaults
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.foundation.combinedClickable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
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
import it.objectfinder.app.data.local.isDefaultCategory
import it.objectfinder.app.data.local.ObjectStatus

@Composable
fun ObjectListScreen(
    viewModel: ObjectListViewModel,
    onOpenObject: (String) -> Unit,
    onCreateObject: () -> Unit,
    modifier: Modifier = Modifier,
    canCreate: Boolean = true,
    /** Stato di sincronizzazione visibile solo con un account: offline non c'è nulla da inviare. */
    showSyncState: Boolean = true,
    /** Dopo modifiche fatte dalla lista (archiviazione multipla): avvia la sincronizzazione. */
    onChanged: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val messages = LocalAppMessages.current
    // Selezione multipla: Set per ricerche immediate; ogni scheda legge il proprio stato in proprio.
    var selected by rememberSaveable(stateSaver = SelectionSaver) { mutableStateOf(emptySet<String>()) }
    val selecting = selected.isNotEmpty()
    BackHandler(enabled = selecting) { selected = emptySet() }
    var choosingPlace by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<Pair<java.io.File, Int>?>(null) }
    var preparing by remember { mutableStateOf(false) }

    fun toggle(id: String) { selected = if (id in selected) selected - id else selected + id }
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
                    quick == null -> messages.locationUnavailable(context)
                }
            }
        }
    }

    Column(modifier.fillMaxSize()) {
      if (selecting) {
        val chosen = state.items.filter { it.id in selected }
        val allArchived = chosen.isNotEmpty() && chosen.all { it.statusEnum == ObjectStatus.ARCHIVED }
        SelectionBar(
            count = selected.size,
            allVisibleSelected = state.items.all { it.id in selected },
            archiveLabel = if (allArchived) "Ripristina" else "Archivia",
            canEdit = canCreate,
            preparing = preparing,
            onClear = { selected = emptySet() },
            onSelectAll = { selected = state.items.map { it.id }.toSet() },
            onLabels = {
                val labels = chosen.map { SheetLabel(it.id, it.name, listOf(state.placePath(it), it.place).filter(String::isNotBlank).joinToString(" · ")) }
                preparing = true
                scope.launch {
                    val file = withContext(Dispatchers.IO) { LabelSheetActions.create(context, labels) }
                    preparing = false
                    sheet = file to labels.size
                }
            },
            onArchive = {
                val ids = selected
                viewModel.setArchived(ids, archived = !allArchived) {
                    selected = emptySet()
                    onChanged()
                    val what = if (ids.size == 1) "1 oggetto" else "${ids.size} oggetti"
                    // Archiviare in blocco è facile da sbagliare: si annulla dalla stessa Snackbar.
                    messages.show(
                        if (allArchived) "$what ripristinati" else "$what archiviati",
                        actionLabel = "Annulla",
                    ) { viewModel.setArchived(ids, archived = allArchived) { onChanged() } }
                }
            },
        )
      } else {
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            placeholder = { Text("Cerca nome, luogo, note o codice") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).widthIn(max = ReadableMaxWidth).fillMaxWidth(),
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
            if (state.places.places.isNotEmpty() || state.placeFilter != null) FilterChip(
                selected = state.placeFilter != null,
                onClick = { choosingPlace = true },
                leadingIcon = { Icon(Icons.Default.Place, contentDescription = null, Modifier.size(16.dp)) },
                label = {
                    Text(
                        state.placeFilter?.let { state.places.path(it) } ?: "Luogo",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 220.dp),
                    )
                },
            )
        }
        if (choosingPlace) PlacePickerSheet(
            tree = state.places,
            selectedId = state.placeFilter,
            canEdit = false,
            title = "Mostra gli oggetti in",
            noneLabel = "Tutti i luoghi",
            onPick = { viewModel.setPlaceFilter(it); choosingPlace = false },
            onCreate = { _, _ -> error("sola lettura") },
            onRename = { _, _ -> },
            onDelete = { },
            onDismiss = { choosingPlace = false },
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
                else -> ObjectGrid(
                    items = state.items,
                    cover = { state.covers[it.id] },
                    distance = state::distanceTo,
                    placePath = state::placePath,
                    showSyncState = showSyncState,
                    isSelected = { it.id in selected },
                    onClick = { if (selecting) toggle(it.id) else onOpenObject(it.id) },
                    onLongClick = { toggle(it.id) },
                )
            }
            if (canCreate && !selecting) ExtendedFloatingActionButton(
                onClick = onCreateObject,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Registra oggetto") },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }
    sheet?.let { (file, count) -> LabelSheetReady(file, count) { sheet = null } }
}

/**
 * Elenco degli oggetti: una colonna su telefono, due o tre su tablet e pieghevoli aperti
 * (schede di almeno 360dp: nome e "dove" restano leggibili per intero).
 */
@Composable
internal fun ObjectGrid(
    items: List<ObjectEntity>,
    cover: (ObjectEntity) -> String?,
    distance: (ObjectEntity) -> Double?,
    placePath: (ObjectEntity) -> String,
    showSyncState: Boolean,
    isSelected: (ObjectEntity) -> Boolean,
    onClick: (ObjectEntity) -> Unit,
    onLongClick: (ObjectEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 360.dp),
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items, key = { it.id }, contentType = { "object" }) { item ->
            ObjectCard(
                item,
                cover = cover(item),
                distance = distance(item),
                placePath = placePath(item),
                showSyncState = showSyncState,
                isSelected = { isSelected(item) },
                onClick = { onClick(item) },
                onLongClick = { onLongClick(item) },
            )
        }
    }
}

@Composable
private fun ObjectCard(
    item: ObjectEntity,
    cover: String?,
    distance: Double?,
    placePath: String,
    showSyncState: Boolean,
    isSelected: () -> Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val chosen = isSelected()
    Card(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "Seleziona")
            .semantics { selected = chosen },
        colors = if (chosen) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        else CardDefaults.cardColors(),
    ) {
      Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        // Riquadro sempre presente: righe allineate con o senza foto.
        Box(
            Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (cover != null) AsyncImage(
                model = File(cover),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            ) else Text(
                item.name.trim().take(1).uppercase(),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (chosen) Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary) }
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
                if (item.isConflict || showSyncState) Icon(
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
            // Il "dove" è la risposta che si cerca: prima riga sotto il nome.
            val where = listOf(placePath, item.place).filter { it.isNotBlank() }.joinToString(" · ")
            if (where.isNotBlank()) {
                Text(
                    where,
                    style = MaterialTheme.typography.bodyMedium,
                    // Due righe: è la risposta che si cerca, non va troncata al primo dettaglio.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (item.hasLocation) Icons.Default.LocationOn else Icons.Default.LocationOff,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (distance != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    locationLine(item, distance),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (distance != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (item.isLoaned) {
                val due = item.loanDue?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
                val today = java.time.LocalDate.now()
                Text(
                    LoanFormat.summary(item.loanedTo, due, today),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (LoanFormat.isOverdue(due, today)) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Solo ciò che distingue l'oggetto: categoria e stato predefiniti sono rumore.
            metaLine(item)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.statusEnum == ObjectStatus.ARCHIVED) MaterialTheme.colorScheme.outline
                    else MaterialTheme.colorScheme.onSurfaceVariant,
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
internal fun EmptyState(title: String, body: String) {
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

/** Distanza se nota, altrimenti solo il fatto che il punto GPS c'è: le coordinate qui non aiutano. */
internal fun locationLine(item: ObjectEntity, distance: Double?): String {
    if (!item.hasLocation) return "Posizione non registrata"
    val reliability = when {
        item.isManualLocation -> "indicata sulla mappa"
        item.isApproximate -> "approssimata ±${item.accuracyMeters!!.toInt()} m"
        else -> null
    }
    return when {
        distance != null -> PlaceFormat.distanceFromYou(distance) + (reliability?.let { " · $it" } ?: "")
        item.isManualLocation -> "Punto indicato sulla mappa"
        item.isApproximate -> "Punto GPS approssimato · ±${item.accuracyMeters!!.toInt()} m"
        else -> "Punto GPS salvato" + (item.accuracyMeters?.let { " · ±${it.toInt()} m" } ?: "")
    }
}

/** Categoria e stato solo se diversi dai valori predefiniti; null se non c'è nulla da dire. */
internal fun metaLine(item: ObjectEntity): String? = listOfNotNull(
    item.category.takeUnless(::isDefaultCategory),
    item.statusEnum.label.takeIf { item.statusEnum != ObjectStatus.ACTIVE },
).joinToString(" · ").ifEmpty { null }

private val SelectionSaver = listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() })

/** Barra della selezione multipla: al posto di ricerca e filtri finché c'è qualcosa selezionato. */
@Composable
private fun SelectionBar(
    count: Int,
    allVisibleSelected: Boolean,
    archiveLabel: String,
    canEdit: Boolean,
    preparing: Boolean,
    onClear: () -> Unit,
    onSelectAll: () -> Unit,
    onLabels: () -> Unit,
    onArchive: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClear) { Icon(Icons.Default.Close, contentDescription = "Annulla selezione") }
            Text(
                if (count == 1) "1 selezionato" else "$count selezionati",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (!allVisibleSelected) TextButton(onClick = onSelectAll) { Text("Seleziona tutti") }
        }
        Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onLabels, enabled = !preparing) {
                if (preparing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.QrCode2, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("Etichette QR")
            }
            if (canEdit) OutlinedButton(onClick = onArchive) {
                Icon(
                    if (archiveLabel == "Archivia") Icons.Default.Archive else Icons.Default.Unarchive,
                    contentDescription = null, Modifier.size(18.dp),
                )
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("$archiveLabel")
            }
        }
    }
}

/** Foglio di etichette pronto: stampa o condivisione del PDF. */
@Composable
private fun LabelSheetReady(file: java.io.File, count: Int, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val messages = LocalAppMessages.current
    val pages = LabelSheetLayout.pageCount(count)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.QrCode2, contentDescription = null) },
        title = { Text("Etichette pronte") },
        text = {
            Text(
                "$count etichette su " + (if (pages == 1) "1 foglio A4" else "$pages fogli A4") +
                    " (3 × 7, circa 70 × 42 mm). Vanno bene fogli adesivi o carta normale da ritagliare."
            )
        },
        confirmButton = { TextButton(onClick = { LabelSheetActions.print(context, file, count); onDismiss() }) { Text("Stampa") } },
        dismissButton = {
            TextButton(onClick = {
                onDismiss()
                if (!LabelSheetActions.share(context, file)) messages.show("Nessuna app disponibile per la condivisione")
            }) { Text("Condividi PDF") }
        },
    )
}
