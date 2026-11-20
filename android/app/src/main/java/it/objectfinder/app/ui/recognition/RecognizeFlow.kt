package it.objectfinder.app.ui.recognition

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import it.objectfinder.app.BuildConfig
import it.objectfinder.app.data.AppContainer
import it.objectfinder.app.data.local.ObjectEntity
import it.objectfinder.app.location.LocationProvider
import it.objectfinder.app.recognition.Confidence
import it.objectfinder.app.recognition.RecognitionMatch
import it.objectfinder.app.recognition.RecognitionResult
import it.objectfinder.app.ui.objects.InAppCamera
import it.objectfinder.app.ui.objects.PlaceFormat
import kotlinx.coroutines.launch
import java.io.File

private sealed interface Step {
    data object Camera : Step
    data class Analyzing(val photo: Uri) : Step
    data class Done(val photo: Uri, val result: RecognitionResult) : Step
}

/**
 * "Riconosci": una foto dell'oggetto, poi gli oggetti del progetto più simili (somiglianza visiva,
 * a parità aiutata dalla distanza). Se nessuno corrisponde si può registrarlo con quella foto.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecognizeFlow(
    container: AppContainer,
    objects: List<ObjectEntity>,
    covers: Map<String, String>,
    onOpenObject: (String) -> Unit,
    onRegisterNew: (Uri) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf<Step?>(null) }
    var pendingSystemPhoto by remember { mutableStateOf<Uri?>(null) }
    val byId = remember(objects) { objects.associateBy { it.id } }

    fun discard(photo: Uri) {
        // Foto temporanea della fotocamera: tenuta solo se diventa la foto di un nuovo oggetto.
        photo.lastPathSegment?.let { File(File(context.cacheDir, "camera"), it).delete() }
    }

    fun analyze(photo: Uri) {
        step = Step.Analyzing(photo)
        scope.launch {
            val here = runCatching { LocationProvider(context).lastKnownLocation() }.getOrNull()
            val result = runCatching { container.recognitionRepository.recognize(photo, here) }
                .getOrDefault(RecognitionResult.Failed)
            step = Step.Done(photo, result)
        }
    }

    val systemCamera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = pendingSystemPhoto
        pendingSystemPhoto = null
        if (ok && uri != null) analyze(uri) else onClose()
    }
    fun useSystemCamera() {
        val uri = container.photoRepository.newCameraUri()
        pendingSystemPhoto = uri
        runCatching { systemCamera.launch(uri) }.onFailure { onClose() }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) step = Step.Camera else useSystemCamera()
    }
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (granted) step = Step.Camera else permission.launch(Manifest.permission.CAMERA)
    }

    when (val current = step) {
        null -> Unit
        Step.Camera -> InAppCamera(
            newTarget = container.photoRepository::newCameraUri,
            onShot = ::analyze,
            onError = { onClose() },
            onDismiss = { if (step == Step.Camera) onClose() },
            onUnavailable = ::useSystemCamera,
            single = true,
            hint = "Inquadra l'oggetto da riconoscere",
        )
        is Step.Analyzing, is Step.Done -> ModalBottomSheet(
            onDismissRequest = {
                (current as? Step.Done)?.photo?.let(::discard) ?: (current as? Step.Analyzing)?.photo?.let(::discard)
                onClose()
            },
        ) {
            Column(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (current is Step.Analyzing) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                        Text("Confronto con le foto dei tuoi oggetti…", style = MaterialTheme.typography.bodyLarge)
                    }
                } else if (current is Step.Done) {
                    val photo = current.photo
                    when (val result = current.result) {
                        is RecognitionResult.Matches -> {
                            Text(
                                if (result.matches.first().confidence == Confidence.HIGH) "Potrebbe essere"
                                else "Forse è uno di questi",
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.semantics { heading() },
                            )
                            result.matches.forEach { match ->
                                val item = byId[match.objectId] ?: return@forEach
                                MatchRow(item, covers[item.id], match) {
                                    discard(photo)
                                    onOpenObject(item.id)
                                }
                            }
                        }
                        RecognitionResult.NoMatch -> Explain(
                            "Non lo riconosco",
                            "Nessun oggetto del progetto somiglia a questa foto. Se è un oggetto nuovo, registralo con questa foto.",
                        )
                        RecognitionResult.NothingToCompare -> Explain(
                            "Ancora niente da confrontare",
                            "Il riconoscimento usa le foto dei tuoi oggetti: aggiungine almeno una agli oggetti da ritrovare.",
                        )
                        RecognitionResult.Failed -> Explain(
                            "Analisi non riuscita",
                            "Non sono riuscito a leggere la foto. Riprova con più luce, inquadrando l'oggetto intero.",
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { discard(photo); step = Step.Camera }, modifier = Modifier.weight(1f)) {
                            Text("Riprova")
                        }
                        Button(onClick = { onRegisterNew(photo) }, modifier = Modifier.weight(1f)) {
                            Text("Registra come nuovo", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Explain(title: String, body: String) {
    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
    Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun MatchRow(item: ObjectEntity, cover: String?, match: RecognitionMatch, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (cover != null) AsyncImage(File(cover), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Text(item.name.take(1).uppercase(), style = MaterialTheme.typography.titleLarge)
        }
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.place.isNotBlank()) {
                Text(item.place, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                listOfNotNull(
                    match.confidence.label,
                    match.distanceMeters?.let(PlaceFormat::distanceFromYou),
                    // Solo negli APK di prova: il valore grezzo, per tarare le soglie sulle foto vere.
                    if (BuildConfig.DEBUG) "somiglianza ${(match.similarity * 100).toInt()}%" else null,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = if (match.confidence == Confidence.HIGH) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
