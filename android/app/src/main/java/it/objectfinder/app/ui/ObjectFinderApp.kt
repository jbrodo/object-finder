package it.objectfinder.app.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import it.objectfinder.app.data.AppContainer
import it.objectfinder.app.data.ObjectEntity
import it.objectfinder.app.location.LocationProvider\nimport it.objectfinder.app.maps.ObjectMapScreen
import it.objectfinder.app.sync.ObjectSyncWorker
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun ObjectFinderApp(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val objects by container.repository.observeObjects().collectAsState(initial = emptyList())
    var name by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("general") }
    var notes by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var locationPermission by remember { mutableStateOf(false) }\n    var showMap by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        locationPermission = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Object Finder") })
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { showMap = false }) { Text("Archivio") }
                TextButton(onClick = { showMap = true }) { Text("Mappa") }
            }
            if (showMap) {
                ObjectMapScreen(objects = objects)
                return@Column
            }
            Text("I tuoi oggetti", style = MaterialTheme.typography.headlineSmall)
            Text("MVP · Archivio locale con sincronizzazione cloud predisposta")
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Nome oggetto") }, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = category, onValueChange = { category = it },
                label = { Text("Categoria") }, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = notes, onValueChange = { notes = it },
                label = { Text("Note") }, modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    permissionLauncher.launch(arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ))
                }) { Text("Abilita GPS") }

                Button(
                    enabled = name.isNotBlank(),
                    onClick = {
                        scope.launch {
                            val location = if (locationPermission) {
                                runCatching { LocationProvider(context).currentLocation() }.getOrNull()
                            } else null
                            val item = ObjectEntity(
                                projectId = "default-project",
                                name = name.trim(),
                                category = category.trim().ifBlank { "general" },
                                notes = notes.trim(),
                                latitude = location?.latitude,
                                longitude = location?.longitude,
                                accuracyMeters = location?.accuracyMeters
                            )
                            container.repository.create(item)
                            name = ""; notes = ""
                            message = if (location != null) "Oggetto salvato con GPS" else "Oggetto salvato senza coordinate GPS"
                        }
                    }
                ) { Text("Registra oggetto") }
            }
            OutlinedButton(
                onClick = {
                    val request = OneTimeWorkRequestBuilder<ObjectSyncWorker>().build()
                    WorkManager.getInstance(context).enqueue(request)
                    message = "Sincronizzazione accodata"
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Sincronizza con Supabase") }

            if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary)
            HorizontalDivider()
            Text("Archivio (${objects.size})", style = MaterialTheme.typography.titleMedium)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(objects, key = { it.id }) { item ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(item.name, style = MaterialTheme.typography.titleMedium)
                            Text("${item.category} · ${item.status}")
                            Text(
                                if (item.latitude != null && item.longitude != null)
                                    "GPS: ${"%.6f".format(item.latitude)}, ${"%.6f".format(item.longitude)} · ±${item.accuracyMeters ?: 0f} m"
                                else "Posizione non acquisita"
                            )
                            if (item.notes.isNotBlank()) Text(item.notes)
                            Text("Sync: ${item.syncStatus}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}
