package it.objectfinder.app.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import it.objectfinder.app.export.ExportFormat
import it.objectfinder.app.export.InventoryExporter
import kotlinx.coroutines.launch

/** Export dell'inventario del progetto attivo: CSV per i fogli di calcolo, PDF stampabile con QR. */
@Composable
fun ExportSection(exporter: InventoryExporter) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var format by rememberSaveable { mutableStateOf(ExportFormat.PDF) }
    var includeArchived by rememberSaveable { mutableStateOf(false) }
    var busy by rememberSaveable { mutableStateOf(false) }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    // "Salva sul telefono": il selettore di sistema sceglie cartella e nome (Download, Drive…).
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(format.mimeType)
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            runCatching {
                val rows = exporter.rows(includeArchived)
                context.contentResolver.openOutputStream(uri)?.use { exporter.writeTo(it, format, rows) }
                    ?: error("destinazione non scrivibile")
                rows.size
            }.onSuccess { toast("Inventario salvato ($it oggetti)") }
                .onFailure { toast("Salvataggio non riuscito: ${it.message}") }
            busy = false
        }
    }

    fun share() {
        busy = true
        scope.launch {
            runCatching { exporter.exportToShareableFile(format, includeArchived) }
                .onSuccess { (uri, count) ->
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = format.mimeType
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, "Inventario ${exporter.projectName} ($count oggetti)")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    try {
                        context.startActivity(Intent.createChooser(send, "Condividi inventario"))
                    } catch (_: ActivityNotFoundException) {
                        toast("Nessuna app disponibile per la condivisione")
                    }
                }
                .onFailure { toast("Export non riuscito: ${it.message}") }
            busy = false
        }
    }

    Text(
        "Oggetti del progetto «${exporter.projectName}». Il PDF include foto, codici e il QR di " +
            "ogni oggetto: scansionando la stampa si apre la sua scheda.",
        style = MaterialTheme.typography.bodyMedium,
    )
    // Scelta esclusiva: pulsanti segmentati con la spunta, come Accedi/Registrati.
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        ExportFormat.entries.forEachIndexed { index, option ->
            SegmentedButton(
                selected = format == option,
                onClick = { format = option },
                shape = SegmentedButtonDefaults.itemShape(index, ExportFormat.entries.size),
            ) { Text(option.label, maxLines = 1) }
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Includi gli oggetti archiviati", Modifier.weight(1f))
        Switch(checked = includeArchived, onCheckedChange = { includeArchived = it })
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = ::share, enabled = !busy) {
            if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            else Icon(Icons.Default.Share, contentDescription = null, Modifier.size(18.dp))
            Text("  Condividi")
        }
        OutlinedButton(onClick = { saveLauncher.launch(exporter.fileName(format)) }, enabled = !busy) {
            Icon(Icons.Default.Save, contentDescription = null, Modifier.size(18.dp))
            Text("  Salva sul telefono")
        }
    }
}
