package it.objectfinder.app.nfc

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.launch

/**
 * Scrive l'etichetta NFC dell'oggetto: finché il dialogo è aperto il telefono è in "reader mode"
 * (il tag non apre altre app) e il primo tag avvicinato viene scritto.
 */
@Composable
fun NfcWriteDialog(objectId: String, title: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    // Si rilegge al ritorno dalle impostazioni NFC.
    val availability = remember(lifecycle) { NfcTags.availability(context) }
    var writing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<NfcTags.WriteResult?>(null) }

    val active = availability == NfcTags.Availability.READY && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    // La sessione NFC dell'app è già attiva: la scrittura prende il primo tag finché il dialogo è aperto.
    DisposableEffect(active) {
        if (active) {
            val message = NfcTags.message(objectId, context.packageName)
            NfcSession.exclusive = { tag ->
                if (!writing) {
                    writing = true
                    scope.launch {
                        result = NfcTags.write(tag, message)
                        writing = false
                    }
                }
            }
        }
        onDispose { NfcSession.exclusive = null }
    }

    val done = result == NfcTags.WriteResult.Written
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Nfc, contentDescription = null) },
        title = { Text("Etichetta NFC") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (availability) {
                    NfcTags.Availability.MISSING -> Text("Questo telefono non ha l'NFC: usa l'etichetta QR.")
                    NfcTags.Availability.DISABLED -> Text("L'NFC è spento. Attivalo nelle impostazioni e torna qui.")
                    NfcTags.Availability.READY -> {
                        Text(
                            "Avvicina un tag NFC al retro del telefono e tienilo fermo: ci scrivo il collegamento a «$title». " +
                                "Il contenuto attuale del tag viene sostituito."
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            val current = result
                            when {
                                writing -> {
                                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                    Text("Scrittura in corso…")
                                }
                                current == null -> {
                                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                    Text("In attesa del tag…")
                                }
                                else -> {
                                    Icon(
                                        if (done) Icons.Default.CheckCircle else Icons.Default.Error,
                                        contentDescription = null,
                                        tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                    )
                                    Text(NfcTags.describe(current))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            when {
                availability == NfcTags.Availability.DISABLED -> TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
                }) { Text("Apri impostazioni") }
                done -> TextButton(onClick = { result = null }) { Text("Scrivi un altro tag") }
                else -> Unit
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (done) "Fatto" else "Chiudi") } },
    )
}
