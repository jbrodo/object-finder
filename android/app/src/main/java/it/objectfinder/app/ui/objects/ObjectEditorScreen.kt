package it.objectfinder.app.ui.objects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.filled.QrCode2
import it.objectfinder.app.qr.QrLabelDialog
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.objectfinder.app.data.local.ObjectStatus
import it.objectfinder.app.ui.common.rememberLocationPermissionRequester
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObjectEditorScreen(
    viewModel: ObjectEditorViewModel,
    onClose: () -> Unit,
    readOnly: Boolean = false,
    objectId: String? = null,
) {
    var showLabel by remember { mutableStateOf(false) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val photos by viewModel.photos.collectAsStateWithLifecycle()
    val photoBusy by viewModel.photoBusy.collectAsStateWithLifecycle()
    val codes by viewModel.identifiers.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val permission = rememberLocationPermissionRequester()

    LaunchedEffect(state.finished) { if (state.finished) onClose() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }
    // Nuovo oggetto: tenta subito l'acquisizione GPS (con il flusso permessi).
    LaunchedEffect(state.isNew) {
        if (state.isNew) permission.withPermission(viewModel::captureLocation)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (readOnly) "Dettaglio oggetto" else if (state.isNew) "Nuovo oggetto" else "Modifica oggetto") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
                    }
                },
                actions = {
                    if (objectId != null && state.loaded && !state.notFound) {
                        IconButton(onClick = { showLabel = true }) {
                            Icon(Icons.Default.QrCode2, contentDescription = "Etichetta QR")
                        }
                    }
                    if (!readOnly && !state.isNew && state.loaded && !state.notFound) {
                        IconButton(onClick = viewModel::toggleArchived) {
                            Icon(
                                if (state.isArchived) Icons.Default.Unarchive else Icons.Default.Archive,
                                contentDescription = if (state.isArchived) "Ripristina" else "Archivia",
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (showLabel && objectId != null) {
            QrLabelDialog(objectId = objectId, title = state.name, onDismiss = { showLabel = false })
        }
        if (state.notFound) {
            Column(Modifier.padding(padding).padding(24.dp)) {
                Text("Oggetto non trovato.")
                TextButton(onClick = onClose) { Text("Torna all'elenco") }
            }
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (readOnly) {
                Text(
                    "Hai accesso in sola lettura a questo progetto.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (state.isArchived) {
                Text(
                    "Oggetto archiviato: non compare nell'elenco principale.",
                    color = MaterialTheme.colorScheme.outline,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::setName,
                enabled = !readOnly,
                label = { Text("Nome oggetto *") },
                singleLine = true,
                isError = state.nameTouched && state.name.isBlank(),
                supportingText = { if (state.nameTouched && state.name.isBlank()) Text("Il nome è obbligatorio") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.category,
                onValueChange = viewModel::setCategory,
                enabled = !readOnly,
                label = { Text("Categoria") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            StatusSelector(state.status, viewModel::setStatus, enabled = !readOnly)
            OutlinedTextField(
                value = state.notes,
                onValueChange = viewModel::setNotes,
                enabled = !readOnly,
                label = { Text("Note") },
                minLines = 3,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )

            PhotoSection(
                photos = photos,
                busy = photoBusy,
                readOnly = readOnly,
                newCameraUri = viewModel::newCameraUri,
                onAdd = viewModel::addPhoto,
                onDelete = viewModel::deletePhoto,
            )

            CodesSection(
                codes = codes,
                readOnly = readOnly,
                onAdd = viewModel::addCode,
                onDelete = viewModel::deleteCode,
                onError = viewModel::showMessage,
            )

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Posizione", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (state.hasLocation) String.format(
                            Locale.ROOT, "%.6f, %.6f", state.latitude, state.longitude
                        ) + (state.accuracyMeters?.let { " · ±${it.toInt()} m" } ?: "")
                        else "Nessuna posizione salvata",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (state.hasLocation) {
                        Button(
                            onClick = { navigateTo(context, state.latitude!!, state.longitude!!) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.Directions, contentDescription = null, Modifier.size(18.dp))
                            Text("  Portami lì")
                        }
                    }
                    if (!readOnly) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { permission.withPermission(viewModel::captureLocation) },
                            enabled = !state.locating,
                        ) {
                            if (state.locating) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Default.MyLocation, contentDescription = null, Modifier.size(18.dp))
                            Text(
                                if (state.hasLocation) "  Aggiorna" else "  Usa posizione attuale",
                            )
                        }
                        if (state.hasLocation) {
                            TextButton(onClick = viewModel::clearLocation) { Text("Rimuovi") }
                        }
                    }
                }
            }

            LocationHistorySection(history)

            if (!readOnly) Button(onClick = viewModel::save, enabled = state.canSave, modifier = Modifier.fillMaxWidth()) {
                Text(
                    when {
                        state.saving && state.locating -> "Attendo la posizione…"
                        state.isNew -> "Registra oggetto"
                        else -> "Salva modifiche"
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatusSelector(selected: ObjectStatus, onSelect: (ObjectStatus) -> Unit, enabled: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    // L'archiviazione ha un'azione dedicata nella top bar.
    val options = ObjectStatus.entries.filter { it != ObjectStatus.ARCHIVED }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = enabled && it }) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text("Stato") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option.label) }, onClick = { onSelect(option); expanded = false })
            }
        }
    }
}
