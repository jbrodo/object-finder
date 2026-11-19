package it.objectfinder.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.objectfinder.app.BuildConfig
import it.objectfinder.app.data.AppContainer
import it.objectfinder.app.data.SyncState
import it.objectfinder.app.maps.MapEngine
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

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
    val session by container.authRepository.session.collectAsStateWithLifecycle()
    val project by container.settings.activeProject.collectAsStateWithLifecycle()
    val syncState by container.settings.syncState.collectAsStateWithLifecycle()
    val pending by container.objectRepository.observePendingCount().collectAsStateWithLifecycle(0)
    val conflicts by container.objectRepository.observeConflictCount().collectAsStateWithLifecycle(0)
    val mapEngine by container.settings.mapEngine.collectAsStateWithLifecycle()

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
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
                OutlinedButton(onClick = { scope.launch { container.authRepository.signOut() } }) { Text("Esci") }
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

        Section("Sincronizzazione") {
            Text("Modifiche da inviare: $pending")
            if (conflicts > 0) {
                Text(
                    "Conflitti da risolvere: $conflicts (oggetti con l'icona di avviso nell'elenco)",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(describe(syncState), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onSyncNow, enabled = session != null && syncState !is SyncState.Running) {
                Text("Sincronizza ora")
            }
        }

        Section("Inventario") {
            ExportSection(container.inventoryExporter)
        }

        Section("Mappa predefinita") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MapEngine.entries.forEach { option ->
                    FilterChip(
                        selected = MapEngine.resolve(mapEngine) == option,
                        enabled = option.isAvailable,
                        onClick = { container.settings.setMapEngine(option) },
                        label = { Text(option.label) },
                    )
                }
            }
            if (!MapEngine.GOOGLE_MAPS.isAvailable) {
                Text("Google Maps richiede una API key nel build.", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "I tile pubblici OpenStreetMap sono destinati a sviluppo/test.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

private fun describe(state: SyncState): String {
    val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    return when (state) {
        SyncState.Idle -> "Nessuna sincronizzazione in questa sessione"
        SyncState.Running -> "Sincronizzazione in corso…"
        is SyncState.Done -> "Ultima sync ${fmt.format(Date(state.atMillis))}: ${state.pushed} inviati, ${state.pulled} ricevuti"
        is SyncState.Failed -> "Sync fallita ${fmt.format(Date(state.atMillis))}: ${state.message}"
    }
}
