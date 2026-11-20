package it.objectfinder.app.ui.objects

import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withTimeoutOrNull
import android.net.Uri
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.viewfinder.compose.MutableCoordinateTransformer
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Fotocamera dentro l'app: anteprima a schermo intero con una cornice guida, flash, più scatti di
 * fila senza uscire. Ogni scatto va a [onShot] (stessa pipeline di galleria e fotocamera di sistema:
 * ridimensionato, senza EXIF). [onError] riceve un messaggio da mostrare se lo scatto non riesce.
 */
@Composable
fun InAppCamera(
    newTarget: () -> Uri,
    onShot: (Uri) -> Unit,
    onError: (String) -> Unit,
    onDismiss: () -> Unit,
    /** La fotocamera in-app non si avvia (dispositivo, driver, emulatore): si usa quella di sistema. */
    onUnavailable: () -> Unit,
    /** Uno scatto solo (riconoscimento): dopo la foto la fotocamera si chiude. */
    single: Boolean = false,
    hint: String? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // Modello della skill CameraX per Compose: Preview → SurfaceRequest → CameraXViewfinder.
    var surfaceRequest by remember { mutableStateOf<SurfaceRequest?>(null) }
    var cameraControl by remember { mutableStateOf<CameraControl?>(null) }
    val coordinateTransformer = remember { MutableCoordinateTransformer() }
    val preview = remember {
        Preview.Builder().build().apply { setSurfaceProvider { request -> surfaceRequest = request } }
    }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setFlashMode(ImageCapture.FLASH_MODE_AUTO)
            .build()
    }
    LaunchedEffect(lifecycleOwner) {
        // Con alcuni driver CameraX riprova all'infinito a inizializzarsi: dopo pochi secondi senza
        // anteprima si passa alla fotocamera di sistema invece di lasciare lo schermo nero.
        val bound = withTimeoutOrNull(START_TIMEOUT_MS) {
            runCatching {
                val provider = ProcessCameraProvider.awaitInstance(context)
                val back = CameraSelector.DEFAULT_BACK_CAMERA
                val selector = if (provider.hasCamera(back)) back else CameraSelector.DEFAULT_FRONT_CAMERA
                if (!provider.hasCamera(selector)) return@runCatching false
                provider.unbindAll()
                cameraControl = provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture).cameraControl
                snapshotFlow { surfaceRequest }.filterNotNull().first()
                true
            }.getOrDefault(false)
        }
        if (bound != true) onUnavailable()
    }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { ProcessCameraProvider.getInstance(context).get().unbind(preview, imageCapture) }
        }
    }
    var shots by remember { mutableIntStateOf(0) }
    var capturing by remember { mutableStateOf(false) }
    var flash by remember { mutableIntStateOf(ImageCapture.FLASH_MODE_AUTO) }

    fun shoot() {
        if (capturing) return
        val target = newTarget()
        val stream = runCatching { context.contentResolver.openOutputStream(target) }.getOrNull()
        if (stream == null) { onError("Impossibile salvare la foto"); return }
        capturing = true
        imageCapture.takePicture(
            ImageCapture.OutputFileOptions.Builder(stream).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    runCatching { stream.close() }
                    capturing = false
                    shots++
                    onShot(target)
                    if (single) onDismiss()
                }

                override fun onError(exception: ImageCaptureException) {
                    runCatching { stream.close() }
                    capturing = false
                    onError("Scatto non riuscito: riprova")
                }
            },
        )
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            surfaceRequest?.let { request ->
                CameraXViewfinder(
                    surfaceRequest = request,
                    coordinateTransformer = coordinateTransformer,
                    // Tocco sull'anteprima: messa a fuoco in quel punto (utile per oggetti piccoli).
                    modifier = Modifier.fillMaxSize().pointerInput(request, cameraControl) {
                        detectTapGestures { offset ->
                            val surface = with(coordinateTransformer) { offset.transform() }
                            val point = SurfaceOrientedMeteringPointFactory(
                                request.resolution.width.toFloat(),
                                request.resolution.height.toFloat(),
                            ).createPoint(surface.x, surface.y)
                            cameraControl?.startFocusAndMetering(
                                FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF).build()
                            )
                        }
                    },
                )
            }
            GuideFrame(Modifier.fillMaxSize())

            val chrome = IconButtonDefaults.iconButtonColors(
                containerColor = Color.Black.copy(alpha = 0.45f),
                contentColor = Color.White,
            )
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(onClick = onDismiss, colors = chrome) { Icon(Icons.Default.Close, contentDescription = "Chiudi fotocamera") }
                IconButton(
                    onClick = {
                        flash = when (flash) {
                            ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                            ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_OFF
                            else -> ImageCapture.FLASH_MODE_AUTO
                        }
                        imageCapture.flashMode = flash
                    },
                    colors = chrome,
                ) {
                    Icon(
                        when (flash) {
                            ImageCapture.FLASH_MODE_ON -> Icons.Default.FlashOn
                            ImageCapture.FLASH_MODE_OFF -> Icons.Default.FlashOff
                            else -> Icons.Default.FlashAuto
                        },
                        contentDescription = when (flash) {
                            ImageCapture.FLASH_MODE_ON -> "Flash attivo"
                            ImageCapture.FLASH_MODE_OFF -> "Flash spento"
                            else -> "Flash automatico"
                        },
                    )
                }
            }

            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    hint ?: if (shots == 0) "Inquadra l'oggetto intero, con un po' di contesto attorno"
                    else "Ancora una da un altro lato, oppure Fatto",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.45f), MaterialTheme.shapes.small)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
                Box(Modifier.fillMaxWidth()) {
                    if (shots > 0) Text(
                        if (shots == 1) "1 foto" else "$shots foto",
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        // Fondo scuro: leggibile anche su un'anteprima chiara.
                        modifier = Modifier.align(Alignment.CenterStart).padding(start = 20.dp)
                            .background(Color.Black.copy(alpha = 0.45f), MaterialTheme.shapes.small)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                    // Otturatore: anello bianco, centro pieno; durante lo scatto un indicatore.
                    Box(
                        Modifier
                            .align(Alignment.Center)
                            .size(76.dp)
                            .border(4.dp, Color.White, CircleShape)
                            .padding(8.dp)
                            .background(if (capturing) Color.White.copy(alpha = 0.5f) else Color.White, CircleShape)
                            .clickable(enabled = !capturing, onClick = ::shoot)
                            .semantics { contentDescription = "Scatta foto" },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (capturing) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp, color = Color.Black)
                    }
                    if (shots > 0) FilledTonalButton(
                        onClick = onDismiss,
                        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp),
                    ) { Text("Fatto") }
                }
            }
        }
    }
}

private const val START_TIMEOUT_MS = 6_000L

/** Cornice guida: rettangolo centrale con angoli marcati, dove mettere l'oggetto. */
@Composable
private fun GuideFrame(modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width * 0.78f
        val h = minOf(size.height * 0.5f, w * 1.25f)
        val topLeft = Offset((size.width - w) / 2, (size.height - h) / 2 - size.height * 0.04f)
        val corner = 28.dp.toPx()
        drawRoundRect(
            Color.White.copy(alpha = 0.35f), topLeft, Size(w, h),
            cornerRadius = CornerRadius(16.dp.toPx()), style = Stroke(1.5.dp.toPx()),
        )
        val stroke = 4.dp.toPx()
        val l = topLeft.x; val t = topLeft.y; val r = l + w; val b = t + h
        listOf(
            Offset(l, t) to listOf(Offset(l + corner, t), Offset(l, t + corner)),
            Offset(r, t) to listOf(Offset(r - corner, t), Offset(r, t + corner)),
            Offset(l, b) to listOf(Offset(l + corner, b), Offset(l, b - corner)),
            Offset(r, b) to listOf(Offset(r - corner, b), Offset(r, b - corner)),
        ).forEach { (origin, ends) -> ends.forEach { drawLine(Color.White, origin, it, stroke) } }
    }
}
