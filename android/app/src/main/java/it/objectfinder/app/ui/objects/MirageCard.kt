package it.objectfinder.app.ui.objects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * "Suggerimenti dalla foto": nomi possibili e testo letto, applicabili con un tocco. Mai automatici:
 * il modello è piccolo e può sbagliare, decide l'utente.
 */
@Composable
fun MirageCard(
    state: MirageUi,
    onDownload: () -> Unit,
    onRetry: () -> Unit,
    onAnalyze: () -> Unit,
    onUseName: (String) -> Unit,
    onAddToNotes: (String) -> Unit,
    onLinkAsCode: (String) -> Unit,
) {
    if (state == MirageUi.Hidden) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, Modifier.size(18.dp))
                Text("Suggerimenti dalla foto", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            }
            when (state) {
                MirageUi.Hidden -> Unit
                MirageUi.Idle -> {
                    Text(
                        "Posso proporre un nome e leggere scritte e numeri di serie dalle foto di questo oggetto.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = onAnalyze) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, Modifier.size(18.dp))
                        Text("  Analizza le foto")
                    }
                }
                MirageUi.NeedsModel -> {
                    Text(
                        "Dalla foto posso proporre un nome e leggere scritte e numeri di serie, tutto sul telefono. " +
                            "Serve scaricare una volta il modello (22 MB).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = onDownload) { Text("Scarica (22 MB)") }
                }
                is MirageUi.Downloading -> {
                    Text("Scarico il modello… ${(state.progress * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                }
                MirageUi.Analyzing -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Guardo le foto…", style = MaterialTheme.typography.bodyMedium)
                }
                is MirageUi.Failed -> {
                    Text(state.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onRetry) { Text("Riprova") }
                }
                is MirageUi.Ready -> {
                    val s = state.suggestions
                    if (s.names.isNotEmpty()) {
                        Text("Nome", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            s.names.forEach { name ->
                                SuggestionChip(onClick = { onUseName(name) }, label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                            }
                        }
                    }
                    if (s.texts.isNotEmpty()) {
                        Text("Testo letto", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            s.texts.forEach { text ->
                                AssistChip(
                                    // Un codice (lettere + cifre) si collega come seriale; il resto va nelle note.
                                    onClick = { if (text.codeLike) onLinkAsCode(text.value) else onAddToNotes(text.value) },
                                    label = { Text(text.value, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    leadingIcon = {
                                        Icon(
                                            if (text.codeLike) Icons.Default.Link else Icons.AutoMirrored.Filled.NoteAdd,
                                            contentDescription = if (text.codeLike) "Collega come numero di serie" else "Aggiungi alle note",
                                            Modifier.size(16.dp),
                                        )
                                    },
                                )
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Tocca per usare. I suggerimenti possono sbagliare.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        // Ricalcolo su tutte le foto (es. dopo averne aggiunta una più nitida).
                        TextButton(onClick = onAnalyze) {
                            Icon(Icons.Default.Refresh, contentDescription = null, Modifier.size(18.dp))
                            Text(" Rianalizza")
                        }
                    }
                }
            }
        }
    }
}
