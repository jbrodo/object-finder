package it.objectfinder.app.ui.objects

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.objectfinder.app.data.local.LocationHistoryEntity
import it.objectfinder.app.data.local.ObjectStatus
import it.objectfinder.app.data.local.PLACE_MAX
import it.objectfinder.app.data.local.isDefaultCategory
import it.objectfinder.app.nfc.NfcWriteDialog
import it.objectfinder.app.qr.QrLabelDialog
import it.objectfinder.app.ui.common.rememberLocationPermissionRequester

/**
 * Scheda oggetto. Un oggetto esistente si apre in consultazione: in cima c'è la risposta a
 * "dov'è?" (luogo, distanza, quando) con "Portami lì"; la modifica è un'azione esplicita.
 * Un oggetto nuovo si apre direttamente nel modulo, con foto e posizione per prime.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObjectEditorScreen(
    viewModel: ObjectEditorViewModel,
    onClose: () -> Unit,
    readOnly: Boolean = false,
    objectId: String? = null,
    canDelete: Boolean = false,
) {
    var showLabel by remember { mutableStateOf(false) }
    var showNfc by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val conflict by viewModel.conflict.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val photos by viewModel.photos.collectAsStateWithLifecycle()
    val photoBusy by viewModel.photoBusy.collectAsStateWithLifecycle()
    val codes by viewModel.identifiers.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val permission = rememberLocationPermissionRequester()
    val editingExisting = state.editing && !state.isNew
    val available = state.loaded && !state.notFound

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

    fun leaveEditing() {
        if (viewModel.hasUnsavedChanges) confirmDiscard = true else viewModel.cancelEditing()
    }
    // Indietro durante la modifica torna alla scheda, senza perdere modifiche in silenzio.
    BackHandler(enabled = editingExisting) { leaveEditing() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            state.isNew -> "Nuovo oggetto"
                            state.editing -> "Modifica oggetto"
                            else -> ""
                        }
                    )
                },
                navigationIcon = {
                    if (editingExisting) IconButton(onClick = ::leaveEditing) {
                        Icon(Icons.Default.Close, contentDescription = "Annulla modifiche")
                    } else IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
                    }
                },
                actions = {
                    if (available && !state.editing) {
                        if (!readOnly) IconButton(onClick = viewModel::startEditing) {
                            Icon(Icons.Default.Edit, contentDescription = "Modifica")
                        }
                        if (objectId != null) IconButton(onClick = { showLabel = true }) {
                            Icon(Icons.Default.QrCode2, contentDescription = "Etichetta QR")
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "Altre azioni")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                // Scrivere un tag non cambia l'oggetto: possibile anche in sola lettura.
                                if (objectId != null) DropdownMenuItem(
                                    text = { Text("Scrivi etichetta NFC") },
                                    leadingIcon = { Icon(Icons.Default.Nfc, contentDescription = null) },
                                    onClick = { menuOpen = false; showNfc = true },
                                )
                                if (!readOnly) DropdownMenuItem(
                                    text = { Text(if (state.isArchived) "Ripristina" else "Archivia") },
                                    leadingIcon = {
                                        Icon(
                                            if (state.isArchived) Icons.Default.Unarchive else Icons.Default.Archive,
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = { menuOpen = false; viewModel.toggleArchived() },
                                )
                                // L'azione irreversibile resta separata e chiede conferma.
                                if (canDelete) DropdownMenuItem(
                                    text = { Text("Elimina definitivamente", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = {
                                        Icon(Icons.Default.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                    },
                                    enabled = !state.saving,
                                    onClick = { menuOpen = false; confirmDelete = true },
                                )
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        // Salva sempre raggiungibile (anche sopra la tastiera), senza scorrere tutto il modulo.
        bottomBar = {
            if (state.editing && !readOnly && available) {
                Surface(tonalElevation = 3.dp, shadowElevation = 3.dp) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .imePadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // Un pulsante spento senza motivo sembra un guasto: si dice cosa manca.
                        if (state.name.isBlank()) {
                            Text(
                                "Scrivi un nome per ${if (state.isNew) "registrare l'oggetto" else "salvare"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }
                        Button(onClick = viewModel::save, enabled = state.canSave, modifier = Modifier.fillMaxWidth()) {
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
        },
    ) { padding ->
        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("Eliminare definitivamente?") },
                text = {
                    Text(
                        "«${state.name}» verrà eliminato con foto, codici collegati e storico posizioni, " +
                            "anche dai dispositivi degli altri membri. Non si può annullare: se vuoi solo " +
                            "nasconderlo, usa Archivia."
                    )
                },
                confirmButton = {
                    TextButton(onClick = { confirmDelete = false; viewModel.deletePermanently() }) {
                        Text("Elimina", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annulla") } },
            )
        }
        if (confirmDiscard) {
            AlertDialog(
                onDismissRequest = { confirmDiscard = false },
                title = { Text("Scartare le modifiche?") },
                text = { Text("Le modifiche non salvate a «${state.name.ifBlank { "questo oggetto" }}» andranno perse.") },
                confirmButton = {
                    TextButton(onClick = { confirmDiscard = false; viewModel.cancelEditing() }) { Text("Scarta") }
                },
                dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Continua a modificare") } },
            )
        }
        if (showNfc && objectId != null) {
            NfcWriteDialog(objectId = objectId, title = state.name, onDismiss = { showNfc = false })
        }
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
                .padding(padding) // include la barra Salva, che segue già la tastiera
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            conflict?.let {
                ConflictBanner(it, readOnly, onKeepMine = viewModel::keepMine, onUseServer = viewModel::useServer)
            }
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (!state.loaded) return@Column

            if (!state.editing) {
                // ---- Consultazione ----
                ObjectHeader(state)
                WherePanel(
                    state = state,
                    history = history,
                    readOnly = readOnly,
                    onMoveHere = { permission.withPermission(viewModel::moveHere) },
                    onDescribe = viewModel::startEditing,
                )
                PhotoSection(
                    photos = photos,
                    busy = photoBusy,
                    readOnly = readOnly,
                    newCameraUri = viewModel::newCameraUri,
                    onAdd = viewModel::addPhoto,
                    onDelete = viewModel::deletePhoto,
                )
                if (state.notes.isNotBlank()) {
                    Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Note", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                        Text(state.notes, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                CodesSection(
                    codes = codes,
                    readOnly = readOnly,
                    onAdd = viewModel::addCode,
                    onDelete = viewModel::deleteCode,
                    onError = viewModel::showMessage,
                )
                LocationHistorySection(history)
            } else {
                // ---- Modulo: nuovo oggetto o modifica ----
                if (state.isNew) {
                    PhotoSection(
                        photos = photos,
                        busy = photoBusy,
                        readOnly = false,
                        newCameraUri = viewModel::newCameraUri,
                        onAdd = viewModel::addPhoto,
                        onDelete = viewModel::deletePhoto,
                    )
                }
                OutlinedTextField(
                    value = state.name,
                    onValueChange = viewModel::setName,
                    label = { Text("Nome oggetto *") },
                    singleLine = true,
                    isError = state.nameTouched && state.name.isBlank(),
                    supportingText = { if (state.nameTouched && state.name.isBlank()) Text("Il nome è obbligatorio") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                PlaceField(state.place, viewModel::setPlace)
                PositionEditor(
                    state = state,
                    onCapture = { permission.withPermission(viewModel::captureLocation) },
                    onClear = viewModel::clearLocation,
                )
                if (state.isNew) {
                    MoreDetails {
                        DetailFields(state, viewModel)
                        CodesSection(
                            codes = codes,
                            readOnly = false,
                            onAdd = viewModel::addCode,
                            onDelete = viewModel::deleteCode,
                            onError = viewModel::showMessage,
                        )
                    }
                } else {
                    DetailFields(state, viewModel)
                }
            }
        }
    }
}

/** Nome e solo i metadati che distinguono l'oggetto (categoria e stato predefiniti non si ripetono). */
@Composable
private fun ObjectHeader(state: EditorState) {
    Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            state.name,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        val meta = listOfNotNull(
            state.category.takeUnless(::isDefaultCategory),
            state.status.label.takeIf { state.status != ObjectStatus.ACTIVE },
        ).joinToString(" · ")
        if (meta.isNotEmpty()) {
            Text(meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * La risposta a "dov'è?": descrizione del punto, distanza e da quando, poi "Portami lì".
 * È l'unica superficie colorata della scheda: si trova a colpo d'occhio.
 */
@Composable
private fun WherePanel(
    state: EditorState,
    history: List<LocationHistoryEntity>,
    readOnly: Boolean,
    onMoveHere: () -> Unit,
    onDescribe: () -> Unit,
) {
    val context = LocalContext.current
    val since = history.maxOfOrNull { it.recordedAt }
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Dove si trova", style = MaterialTheme.typography.labelLarge, modifier = Modifier.semantics { heading() })
            if (state.place.isNotBlank()) {
                Text(state.place, style = MaterialTheme.typography.titleLarge)
            } else if (!readOnly) {
                // Allineato al resto del pannello: niente rientro del TextButton.
                TextButton(onClick = onDescribe, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp)) {
                    Icon(Icons.Default.Add, contentDescription = null, Modifier.size(18.dp))
                    Text("  Descrivi il punto (es. garage, scaffale alto)")
                }
            }
            if (state.hasLocation) {
                val facts = listOfNotNull(
                    state.distanceMeters?.let(PlaceFormat::distanceFromYou),
                    since?.let { "aggiornata ${PlaceFormat.age(it, System.currentTimeMillis())}" },
                ).joinToString(" · ")
                if (facts.isNotEmpty()) Text(facts, style = MaterialTheme.typography.bodyLarge)
                Text(
                    PlaceFormat.coordinates(state.latitude!!, state.longitude!!, state.accuracyMeters),
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = { navigateTo(context, state.latitude!!, state.longitude!!) },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                ) {
                    Icon(Icons.Default.Directions, contentDescription = null, Modifier.size(18.dp))
                    Text("  Portami lì")
                }
            } else {
                Text("Posizione GPS non registrata", style = MaterialTheme.typography.bodyLarge)
            }
            if (!readOnly) {
                OutlinedButton(
                    onClick = onMoveHere,
                    enabled = !state.locating,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.locating) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.MyLocation, contentDescription = null, Modifier.size(18.dp))
                    Text(
                        when {
                            state.locating -> "  Rilevo la posizione…"
                            state.hasLocation -> "  L'ho spostato qui"
                            else -> "  Registra la posizione qui"
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaceField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text("Dove si trova") },
        placeholder = { Text("es. garage, scaffale in alto a sinistra") },
        supportingText = {
            Text(
                if (value.length > PLACE_MAX - 40) "${value.length}/$PLACE_MAX"
                else "Al chiuso il GPS sbaglia di qualche metro: una frase aiuta a ritrovarlo"
            )
        },
        maxLines = 3,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Punto GPS nel modulo: stato dell'acquisizione sempre visibile. */
@Composable
private fun PositionEditor(state: EditorState, onCapture: () -> Unit, onClear: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Punto GPS", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            Text(
                when {
                    state.locating -> "Rilevo la posizione…"
                    state.hasLocation -> PlaceFormat.coordinates(state.latitude!!, state.longitude!!, state.accuracyMeters)
                    else -> "Nessuna posizione salvata"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onCapture, enabled = !state.locating) {
                    if (state.locating) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.MyLocation, contentDescription = null, Modifier.size(18.dp))
                    Text(if (state.hasLocation) "  Aggiorna" else "  Usa posizione attuale")
                }
                Spacer(Modifier.weight(1f))
                // Lontano da "Aggiorna": toglie un dato, non deve partire per sbaglio.
                if (state.hasLocation && !state.locating) {
                    TextButton(onClick = onClear) { Text("Rimuovi", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

@Composable
private fun DetailFields(state: EditorState, viewModel: ObjectEditorViewModel) {
    OutlinedTextField(
        value = state.category,
        onValueChange = viewModel::setCategory,
        label = { Text("Categoria") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    StatusSelector(state.status, viewModel::setStatus)
    OutlinedTextField(
        value = state.notes,
        onValueChange = viewModel::setNotes,
        label = { Text("Note") },
        minLines = 3,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Categoria, stato, note e codici hanno già un valore o sono facoltativi: si aprono su richiesta. */
@Composable
private fun MoreDetails(content: @Composable () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) {
        Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
        Text(
            if (open) "  Meno dettagli" else "  Altri dettagli: categoria, stato, note, codici",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (open) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatusSelector(selected: ObjectStatus, onSelect: (ObjectStatus) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    // L'archiviazione ha un'azione dedicata nel menu della scheda.
    val options = ObjectStatus.entries.filter { it != ObjectStatus.ARCHIVED }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Stato") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option.label) }, onClick = { onSelect(option); expanded = false })
            }
        }
    }
}
