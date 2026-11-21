package it.objectfinder.app.ui.objects

import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.objectfinder.app.recognition.PlantGuess
import it.objectfinder.app.recognition.PlantSource
import kotlin.math.roundToInt

/**
 * Riconoscimento delle piante dentro "Suggerimenti dalla foto", solo su richiesta: un pulsante
 * discreto finché non lo si tocca (online consuma la quota giornaliera), poi i candidati con la
 * percentuale; un tocco ne usa uno (nome, categoria, specie nelle note).
 */
@Composable
fun PlantSuggestions(
    state: PlantUi,
    onIdentify: () -> Unit,
    onDownload: () -> Unit,
    onUse: (PlantGuess) -> Unit,
) {
    if (state == PlantUi.Hidden) return
    if (state == PlantUi.Idle) {
        TextButton(onClick = onIdentify) {
            Icon(Icons.Default.Eco, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text("È una pianta? Riconoscila")
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Eco, contentDescription = null, Modifier.size(16.dp))
            Text("Pianta", style = MaterialTheme.typography.labelLarge, modifier = Modifier.semantics { heading() })
        }
        when (state) {
            PlantUi.Hidden, PlantUi.Idle -> Unit
            is PlantUi.NeedsModel -> {
                state.note?.let { Muted(it) }
                Muted(
                    "Senza connessione riconosco piante dell'orto e del giardino (flora europea) con un modello " +
                        "sul telefono, da scaricare una volta (50 MB). Le piante d'appartamento si riconoscono meglio online."
                )
                OutlinedButton(onClick = onDownload) { Text("Scarica (50 MB)") }
            }
            is PlantUi.Downloading -> {
                Text("Scarico il modello… ${(state.progress * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
            }
            PlantUi.Identifying -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("Guardo la foto…", style = MaterialTheme.typography.bodyMedium)
            }
            PlantUi.NotAPlant -> {
                Muted("Non riconosco una pianta in questa foto. Prova con una foto ravvicinata di foglie o fiori.")
                Retry(onIdentify)
            }
            is PlantUi.Failed -> {
                Muted(state.message)
                Retry(onIdentify)
            }
            is PlantUi.Ready -> {
                val r = state.result
                state.note?.let { Muted(it) }
                if (!r.confident) Muted("Non sono sicuro: controlla prima di usare un nome.")
                if (r.source == PlantSource.OFFLINE) {
                    Muted("Sul telefono conosco la flora europea: piante d'appartamento e molte da orto non le riconosco.")
                }
                r.guesses.forEach { guess -> GuessRow(guess, onClick = { onUse(guess) }) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${r.source.label} · tocca per usare",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Retry(onIdentify)
                }
            }
        }
    }
}

@Composable
private fun GuessRow(guess: PlantGuess, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (guess.genusOnly) "${guess.scientificName} (genere)" else guess.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val latin = if (guess.commonNames.isNotEmpty()) guess.scientificName else null
            Text(
                listOfNotNull(latin, guess.family.takeIf { it.isNotBlank() }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                fontStyle = if (latin != null) FontStyle.Italic else null,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            "${(guess.probability * 100).roundToInt()}%",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun Muted(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Retry(onClick: () -> Unit) = TextButton(onClick = onClick) {
    Icon(Icons.Default.Refresh, contentDescription = null, Modifier.size(18.dp))
    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
    Text("Riprova")
}
