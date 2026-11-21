package it.objectfinder.app.ui.settings

import it.objectfinder.app.ui.common.readableWidth
import it.objectfinder.app.ui.auth.NewPasswordDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import it.objectfinder.app.ui.objects.PlaceFormat
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.objectfinder.app.BuildConfig
import it.objectfinder.app.data.AppContainer
import it.objectfinder.app.data.SyncState
import it.objectfinder.app.maps.MapEngine
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    container: AppContainer,
    onSignIn: () -> Unit,
    onSyncNow: () -> Unit,
    onOpenProjects: () -> Unit,
    onOpenMembers: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var confirmSignOut by remember { mutableStateOf(false) }
    var changingPassword by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val session by container.authRepository.session.collectAsStateWithLifecycle()
    val project by container.settings.activeProject.collectAsStateWithLifecycle()
    val syncState by container.settings.syncState.collectAsStateWithLifecycle()
    val pending by container.objectRepository.observePendingCount().collectAsStateWithLifecycle(0)
    val conflicts by container.objectRepository.observeConflictCount().collectAsStateWithLifecycle(0)
    val mapEngine by container.settings.mapEngine.collectAsStateWithLifecycle()

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).readableWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Section("Account") {
            val current = session
            if (current == null) {
                Text("Non hai effettuato l'accesso: gli oggetti restano solo su questo dispositivo.")
                Button(onClick = onSignIn, enabled = container.config.isSupabaseConfigured) { Text("Accedi o registrati") }
                if (!container.config.isSupabaseConfigured) {
                    Text(
                        "Supabase non configurato in questo build.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                Text(current.email.ifBlank { current.userId })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { changingPassword = true }) { Text("Cambia password") }
                    OutlinedButton(onClick = { confirmSignOut = true }) { Text("Esci") }
                }
                if (changingPassword) NewPasswordDialog(
                    title = "Cambia password",
                    message = null,
                    email = current.email,
                    onSave = container.authRepository::changePassword,
                    onDone = { text ->
                        changingPassword = false
                        android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
                    },
                    onDismiss = { changingPassword = false },
                )
                if (confirmSignOut) AlertDialog(
                    onDismissRequest = { confirmSignOut = false },
                    title = { Text("Uscire dall'account?") },
                    text = {
                        Text(
                            "Gli oggetti sincronizzati vengono tolti da questo telefono e restano sul server: " +
                                "li ritrovi quando rientri." +
                                if (pending > 0) " Le $pending modifiche non ancora inviate restano qui e partiranno " +
                                    "quando rientri con ${current.email.ifBlank { "questo account" }}."
                                else ""
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            confirmSignOut = false
                            scope.launch { container.objectRepository.signOut() }
                        }) { Text("Esci") }
                    },
                    dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Annulla") } },
                )
            }
        }

        if (session != null) {
            Section("Progetto") {
                val active = project
                Text(active?.name ?: "In preparazione…", style = MaterialTheme.typography.bodyLarge)
                active?.role?.let {
                    Text("Il tuo ruolo: ${it.label}", style = MaterialTheme.typography.bodyMedium)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onOpenProjects) { Text("Cambia o crea") }
                    OutlinedButton(onClick = onOpenMembers, enabled = active?.role != null) { Text("Membri") }
                }
            }
        }

        // Senza account non c'è nulla da inviare: niente contatori o pulsanti che promettono una sync.
        if (session != null) Section("Sincronizzazione") {
            // Stato in parole: "tutto a posto" o cosa manca, non contatori a zero.
            Text(
                when {
                    syncState is SyncState.Running -> "Sincronizzazione in corso…"
                    pending == 1 -> "1 modifica da inviare"
                    pending > 0 -> "$pending modifiche da inviare"
                    syncState is SyncState.Failed -> "Ultima sincronizzazione non riuscita"
                    else -> "Tutto sincronizzato"
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            if (conflicts > 0) {
                Text(
                    "Conflitti da risolvere: $conflicts (oggetti con l'icona di avviso nell'elenco)",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(describe(syncState), style = MaterialTheme.typography.bodyMedium)
            // Secondario: la sincronizzazione parte da sola; il pulsante serve a forzarla.
            OutlinedButton(onClick = onSyncNow, enabled = syncState !is SyncState.Running) {
                Text("Sincronizza ora")
            }
        }

        // Scelta del motore solo se ce n'è più di uno disponibile in questo build.
        if (MapEngine.available.size > 1) Section("Mappa predefinita") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MapEngine.available.forEach { option ->
                    FilterChip(
                        selected = MapEngine.resolve(mapEngine) == option,
                        onClick = { container.settings.setMapEngine(option) },
                        label = { Text(option.label) },
                    )
                }
            }
        }

        Text(
            "Versione ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

private fun describe(state: SyncState): String = when (state) {
    SyncState.Idle -> "Si sincronizza da sola quando sei online."
    SyncState.Running -> "Invio e ricezione delle modifiche…"
    is SyncState.Done -> "Ultima volta: ${PlaceFormat.dateTime(state.atMillis)}" +
        listOfNotNull(
            state.pushed.takeIf { it > 0 }?.let { "$it inviate" },
            state.pulled.takeIf { it > 0 }?.let { "$it ricevute" },
        ).joinToString(", ").let { if (it.isEmpty()) "" else " · $it" }
    is SyncState.Failed -> "${PlaceFormat.dateTime(state.atMillis)}: ${state.message}"
}
