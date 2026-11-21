package it.objectfinder.app.ui.objects

import androidx.compose.material.icons.filled.QrCode2
import it.objectfinder.app.ui.common.CollapsibleCard
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import it.objectfinder.app.nfc.NfcTags
import it.objectfinder.app.nfc.NfcSession
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material.icons.filled.Nfc
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import it.objectfinder.app.data.Identifiers
import it.objectfinder.app.data.local.IdentifierEntity
import it.objectfinder.app.data.local.IdentifierType
import it.objectfinder.app.qr.CodeScanner

/**
 * Codici già presenti sull'oggetto (barcode del prodotto, numero di serie, QR di terzi):
 * una volta collegati, scansionarli dall'app apre l'oggetto senza bisogno di etichette.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CodesSection(
    codes: List<IdentifierEntity>,
    readOnly: Boolean,
    onAdd: (IdentifierType, String) -> Unit,
    onDelete: (String) -> Unit,
    onError: (String) -> Unit,
    /** In consultazione: chiusa, con il numero di codici. Nel modulo resta aperta. */
    collapsible: Boolean = false,
) {
    val activity = LocalActivity.current as? ComponentActivity
    var manualEntry by rememberSaveable { mutableStateOf(false) }
    var waitingTag by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val hasNfc = remember(context) { NfcTags.availability(context) != NfcTags.Availability.MISSING }
    var confirmDelete by remember { mutableStateOf<IdentifierEntity?>(null) }

    CollapsibleCard(
        title = "Codici collegati",
        icon = Icons.Default.QrCode2,
        summary = when (codes.size) { 0 -> "Nessuno"; 1 -> "1 collegato"; else -> "${codes.size} collegati" },
        collapsible = collapsible,
    ) {
        if (codes.isEmpty()) {
            Text(
                if (readOnly) "Nessun codice collegato"
                else "Collega il barcode del prodotto, il numero di serie o un tag NFC: scansionandoli ritroverai l'oggetto.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                codes.forEach { code ->
                    InputChip(
                        selected = false,
                        onClick = { if (!readOnly) confirmDelete = code },
                        // Valori lunghi (URL, UID): una riga con i puntini, non tagliata a metà.
                        label = {
                            Text(
                                "${code.typeEnum.label}: ${code.value}",
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                softWrap = false,
                            )
                        },
                        trailingIcon = if (readOnly) null else {
                            { Icon(Icons.Default.Close, contentDescription = "Rimuovi codice", Modifier.size(16.dp)) }
                        },
                    )
                }
            }
        }
        if (!readOnly) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = activity != null,
                    onClick = {
                        activity?.let { act ->
                            CodeScanner.scan(
                                act,
                                onResult = { code ->
                                    onAdd(if (code.isQr) IdentifierType.QR else IdentifierType.BARCODE, code.raw)
                                },
                                onError = onError,
                            )
                        }
                    },
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("Scansiona")
                }
                OutlinedButton(onClick = { manualEntry = true }) {
                    Icon(Icons.Default.Keyboard, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("Scrivi")
                }
                if (hasNfc) OutlinedButton(onClick = { waitingTag = true }) {
                    Icon(Icons.Default.Nfc, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("Tag NFC")
                }
            }
        }
    }

    if (waitingTag) {
        // Un tag esistente (anche in sola lettura) si collega con il suo identificativo, senza riscriverlo.
        DisposableEffect(Unit) {
            NfcSession.exclusive = { tag ->
                val read = NfcSession.read(tag)
                waitingTag = false
                when {
                    read.link != null -> onError("È un'etichetta Object Finder: identifica già un oggetto, non va collegata.")
                    read.uid.isEmpty() -> onError("Questo tag non ha un identificativo leggibile: usa un'etichetta QR.")
                    else -> onAdd(IdentifierType.NFC, read.uid)
                }
            }
            onDispose { NfcSession.exclusive = null }
        }
        AlertDialog(
            onDismissRequest = { waitingTag = false },
            icon = { Icon(Icons.Default.Nfc, contentDescription = null) },
            title = { Text("Collega un tag NFC") },
            text = {
                Text(
                    "Avvicina al retro del telefono il tag da collegare: biglietto da visita, portachiavi o " +
                        "qualsiasi tag NFC. Non viene modificato: si usa il suo identificativo."
                )
            },
            confirmButton = { TextButton(onClick = { waitingTag = false }) { Text("Annulla") } },
        )
    }

    if (manualEntry) {
        ManualCodeDialog(
            onDismiss = { manualEntry = false },
            onConfirm = { type, value -> manualEntry = false; onAdd(type, value) },
        )
    }
    confirmDelete?.let { code ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Scollegare il codice?") },
            text = { Text("${code.typeEnum.label}: ${code.value}") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = null; onDelete(code.id) }) {
                    Text("Scollega", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Annulla") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ManualCodeDialog(onDismiss: () -> Unit, onConfirm: (IdentifierType, String) -> Unit) {
    var type by rememberSaveable { mutableStateOf(IdentifierType.SERIAL) }
    var value by rememberSaveable { mutableStateOf("") }
    val valid = value.isNotBlank() && value.trim().length <= Identifiers.MAX_LENGTH
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scrivi codice") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IdentifierType.manual.forEach { option ->
                        FilterChip(selected = type == option, onClick = { type = option }, label = { Text(option.label) })
                    }
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(type.label) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = if (type == IdentifierType.SERIAL) KeyboardCapitalization.Characters
                        else KeyboardCapitalization.None,
                    ),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(type, value) }, enabled = valid) { Text("Collega") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}
