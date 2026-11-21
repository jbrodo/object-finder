package it.objectfinder.app.ui.objects

import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import it.objectfinder.app.data.userMessage
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.objectfinder.app.data.PlaceTree
import it.objectfinder.app.data.local.PlaceEntity
import kotlinx.coroutines.launch

/**
 * Selettore del luogo: albero con rientri, ricerca, "Nessun luogo", creazione di un luogo dentro
 * quello selezionato; per chi può modificare, rinomina ed eliminazione dal menu di ogni riga.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlacePickerSheet(
    tree: PlaceTree,
    selectedId: String?,
    canEdit: Boolean,
    title: String = "Luogo",
    noneLabel: String = "Nessun luogo",
    onPick: (String?) -> Unit,
    onCreate: suspend (name: String, parentId: String?) -> PlaceEntity,
    onRename: suspend (id: String, name: String) -> Unit,
    onDelete: suspend (id: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // Esito delle modifiche ai luoghi, nel foglio stesso: una Snackbar resterebbe sotto lo scrim.
    var failure by remember { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<PlaceEntity?>(null) }
    var deleting by remember { mutableStateOf<PlaceEntity?>(null) }
    val rows = tree.flattened().let { all ->
        if (query.isBlank()) all
        else all.filter { (p, _) -> tree.path(p.id).contains(query.trim(), ignoreCase = true) }.map { it.first to 0 }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            failure?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            if (tree.places.size > 6) OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Cerca luogo") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                item(key = "none", contentType = "none") {
                    PlaceRow(name = noneLabel, depth = 0, selected = selectedId == null, muted = true, onClick = { onPick(null) })
                }
                items(rows, key = { it.first.id }, contentType = { "place" }) { (place, depth) ->
                    PlaceRow(
                        name = if (query.isBlank()) place.name else tree.path(place.id),
                        depth = depth,
                        selected = place.id == selectedId,
                        onClick = { onPick(place.id) },
                        menu = if (canEdit) {
                            { close ->
                                DropdownMenuItem(
                                    text = { Text("Rinomina") },
                                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                    onClick = { close(); renaming = place },
                                )
                                DropdownMenuItem(
                                    text = { Text("Elimina", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                    onClick = { close(); deleting = place },
                                )
                            }
                        } else null,
                    )
                }
            }
            if (tree.places.isEmpty()) Text(
                "Nessun luogo ancora: crea i luoghi della casa o dell'ufficio (es. Casa › Garage › Scaffale).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (canEdit) OutlinedButton(onClick = { creating = true }) {
                Icon(Icons.Default.Add, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(
                    if (selectedId != null) "Nuovo luogo dentro ${tree.get(selectedId)?.name.orEmpty()}" else "Nuovo luogo",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    if (creating) NameDialog(
        title = if (selectedId != null) "Nuovo luogo in ${tree.path(selectedId)}" else "Nuovo luogo",
        initial = "",
        onDismiss = { creating = false },
        onConfirm = { name ->
            creating = false
            failure = null
            scope.launch {
                runCatching { onCreate(name, selectedId) }
                    .onSuccess { onPick(it.id) }
                    .onFailure { failure = it.userMessage("Luogo non creato: riprova") }
            }
        },
    )
    renaming?.let { place ->
        NameDialog(
            title = "Rinomina luogo",
            initial = place.name,
            onDismiss = { renaming = null },
            onConfirm = { name ->
                renaming = null
                failure = null
                scope.launch { runCatching { onRename(place.id, name) }.onFailure { failure = it.userMessage("Luogo non rinominato: riprova") } }
            },
        )
    }
    deleting?.let { place ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Eliminare «${place.name}»?") },
            text = {
                Text(
                    "Gli oggetti che ci sono dentro non vengono eliminati: restano senza luogo. " +
                        "I luoghi al suo interno salgono di un livello."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    failure = null
                    scope.launch {
                        // La scelta si toglie solo se il luogo è stato davvero eliminato.
                        runCatching { onDelete(place.id) }
                            .onSuccess { if (place.id == selectedId) onPick(null) }
                            .onFailure { failure = it.userMessage("Luogo non eliminato: riprova") }
                    }
                }) { Text("Elimina", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Annulla") } },
        )
    }
}

@Composable
private fun PlaceRow(
    name: String,
    depth: Int,
    selected: Boolean,
    onClick: () -> Unit,
    muted: Boolean = false,
    menu: (@Composable (close: () -> Unit) -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick)
            .semantics { this.selected = selected }
            .padding(start = (depth * 20).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (selected) Icons.Default.Check else Icons.Default.Place,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp).size(20.dp),
        )
        Text(
            name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.SemiBold else null,
            color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (menu != null) Box {
            var open by remember { mutableStateOf(false) }
            IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Azioni su $name") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) { menu { open = false } }
        }
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(PlaceTree.NAME_MAX) },
                label = { Text("Nome") },
                placeholder = { Text("es. Garage") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text("Salva") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}
