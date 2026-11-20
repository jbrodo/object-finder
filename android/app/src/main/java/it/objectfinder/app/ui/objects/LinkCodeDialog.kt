package it.objectfinder.app.ui.objects

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.objectfinder.app.data.local.IdentifierType
import it.objectfinder.app.data.local.ObjectEntity
import it.objectfinder.app.data.local.ObjectStatus

/**
 * Codice scansionato o tag NFC non collegato a nessun oggetto: invece di un semplice messaggio si
 * sceglie subito l'oggetto a cui collegarlo (poi scansionandolo si aprirà quello).
 */
@Composable
fun LinkCodeDialog(
    code: String,
    type: IdentifierType,
    objects: List<ObjectEntity>,
    canLink: Boolean,
    onPick: (ObjectEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val candidates = objects
        .filter { it.statusEnum != ObjectStatus.ARCHIVED }
        .filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) || it.place.contains(query.trim(), ignoreCase = true) }
        .sortedBy { it.name.lowercase() }
    val what = if (type == IdentifierType.NFC) "Questo tag NFC" else "Il codice"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (type == IdentifierType.NFC) "Tag NFC non collegato" else "Codice non collegato") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "$what non è collegato a nessun oggetto" +
                        if (canLink) ". Scegli a quale collegarlo: la prossima volta si aprirà direttamente." else ".",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    code.take(80),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (canLink) {
                    if (objects.isEmpty()) {
                        Text("Nessun oggetto nel progetto: registrane uno e poi collega il codice dalla sua scheda.")
                    } else {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("Cerca oggetto") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        LazyColumn(Modifier.heightIn(max = 280.dp)) {
                            items(candidates, key = { it.id }) { item ->
                                ListItem(
                                    headlineContent = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    supportingContent = item.place.takeIf { it.isNotBlank() }?.let { place ->
                                        { Text(place, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                    },
                                    // Stesso fondo del dialogo: le righe non sembrano un riquadro a parte.
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                    modifier = Modifier.clickable { onPick(item) },
                                )
                            }
                        }
                        if (candidates.isEmpty()) Text("Nessun oggetto corrisponde alla ricerca.")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Chiudi") } },
    )
}
