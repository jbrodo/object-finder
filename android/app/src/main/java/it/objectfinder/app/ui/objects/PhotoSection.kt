package it.objectfinder.app.ui.objects

import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import it.objectfinder.app.ui.common.LocalAppMessages
import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import it.objectfinder.app.data.local.PhotoEntity
import it.objectfinder.app.data.local.PhotoSyncStatus
import java.io.File

@Composable
fun PhotoSection(
    photos: List<PhotoEntity>,
    busy: Boolean,
    readOnly: Boolean,
    newCameraUri: () -> Uri,
    onAdd: (Uri) -> Unit,
    onDelete: (String) -> Unit,
) {
    val context = LocalContext.current
    val messages = LocalAppMessages.current
    var pendingCameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    var viewing by rememberSaveable { mutableStateOf<String?>(null) }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = pendingCameraUri?.let(Uri::parse)
        pendingCameraUri = null
        if (ok && uri != null) onAdd(uri)
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onAdd(uri)
    }
    var inAppCamera by rememberSaveable { mutableStateOf(false) }

    /** Fotocamera di sistema: ripiego se il permesso della fotocamera in-app è negato. */
    fun systemCamera() {
        val uri = newCameraUri()
        pendingCameraUri = uri.toString()
        try {
            camera.launch(uri)
        } catch (_: ActivityNotFoundException) {
            pendingCameraUri = null
            messages.show("Nessuna app fotocamera disponibile")
        }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) inAppCamera = true else systemCamera()
    }
    if (inAppCamera) {
        InAppCamera(
            newTarget = newCameraUri,
            onShot = onAdd,
            onDismiss = { inAppCamera = false },
            onUnavailable = { inAppCamera = false; systemCamera() },
        )
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Foto", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            }
            if (photos.isEmpty()) {
                Text(
                    if (readOnly) "Nessuna foto" else "Aggiungi una o più foto per riconoscere l'oggetto.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(photos, key = { it.id }, contentType = { "photo" }) { photo ->
                        PhotoThumbnail(photo, size = 96, onClick = { viewing = photo.id })
                    }
                }
            }
            if (!readOnly) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                                context, android.Manifest.permission.CAMERA,
                            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                            if (granted) inAppCamera = true
                            else cameraPermission.launch(android.Manifest.permission.CAMERA)
                        },
                    ) {
                        Icon(Icons.Default.PhotoCamera, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text("Scatta")
                    }
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                    ) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text("Galleria")
                    }
                }
            }
        }
    }

    val current = photos.firstOrNull { it.id == viewing }
    if (current != null) {
        PhotoViewer(
            photo = current,
            canDelete = !readOnly,
            onDismiss = { viewing = null },
            onDelete = { viewing = null; onDelete(current.id) },
        )
    }
}

@Composable
fun PhotoThumbnail(photo: PhotoEntity, size: Int, onClick: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .size(size.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
    ) {
        photo.localPath?.let { path ->
            AsyncImage(
                model = File(path),
                contentDescription = "Foto oggetto",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } ?: Icon(
            // Sul server ma non ancora sul telefono: si scarica aprendo la scheda (serve la rete).
            Icons.Default.CloudDownload,
            contentDescription = "Foto non ancora scaricata",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.Center).size(24.dp),
        )
        if (photo.syncStatus == PhotoSyncStatus.PENDING_UPLOAD.wire) {
            Icon(
                Icons.Default.CloudUpload,
                contentDescription = "Da caricare",
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .size(18.dp)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
                    .padding(2.dp),
            )
        }
    }
}

@Composable
private fun PhotoViewer(photo: PhotoEntity, canDelete: Boolean, onDismiss: () -> Unit, onDelete: () -> Unit) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Eliminare la foto?") },
            text = { Text("La foto verrà rimossa da questo dispositivo e, dopo la sincronizzazione, dal cloud.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("Elimina", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annulla") } },
        )
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(
            usePlatformDefaultWidth = false,
            // Schermo intero edge-to-edge: si disegna sotto le barre, i comandi usano gli inset.
            decorFitsSystemWindows = false,
        )) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            photo.localPath?.let { path ->
                AsyncImage(
                    model = File(path),
                    contentDescription = "Foto oggetto",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            val buttonColors = IconButtonDefaults.iconButtonColors(
                containerColor = Color.Black.copy(alpha = 0.5f),
                contentColor = Color.White,
            )
            IconButton(onClick = onDismiss, colors = buttonColors, modifier = Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(16.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Chiudi")
            }
            if (canDelete) {
                IconButton(onClick = { confirmDelete = true }, colors = buttonColors, modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(16.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Elimina foto")
                }
            }
        }
    }
}
