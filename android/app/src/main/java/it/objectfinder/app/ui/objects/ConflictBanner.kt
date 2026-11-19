package it.objectfinder.app.ui.objects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Un altro membro ha modificato (o eliminato) l'oggetto mentre qui c'erano modifiche non inviate:
 * nessuna delle due versioni viene persa finché l'utente non sceglie.
 */
@Composable
fun ConflictBanner(
    conflict: ConflictUi,
    readOnly: Boolean,
    onKeepMine: () -> Unit,
    onUseServer: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = null, Modifier.size(20.dp))
                Text(
                    if (conflict.deletedOnServer) "  Eliminato da un altro membro" else "  Modificato da un altro membro",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Text(
                if (conflict.deletedOnServer)
                    "Mentre modificavi questo oggetto, è stato eliminato dal progetto. Puoi ricrearlo con la tua versione o eliminarlo anche qui."
                else
                    "Le tue modifiche non sono ancora state inviate perché nel frattempo l'oggetto è cambiato sul server. Scegli quale versione tenere.",
                style = MaterialTheme.typography.bodyMedium,
            )
            conflict.differences.forEach { d ->
                Column {
                    Text(d.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Text("Tua: ${d.mine}", style = MaterialTheme.typography.bodySmall)
                    Text("Server: ${d.theirs}", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (!readOnly) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onKeepMine) {
                        Text(if (conflict.deletedOnServer) "Ricrea" else "Tieni la mia")
                    }
                    OutlinedButton(onClick = onUseServer) {
                        Text(if (conflict.deletedOnServer) "Elimina anche qui" else "Usa quella del server")
                    }
                }
            } else {
                OutlinedButton(onClick = onUseServer) { Text("Usa la versione del server") }
            }
        }
    }
}
