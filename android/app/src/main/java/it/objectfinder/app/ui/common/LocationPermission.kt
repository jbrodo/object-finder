package it.objectfinder.app.ui.common

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import it.objectfinder.app.location.hasLocationPermission

private val LOCATION_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

@Stable
class LocationPermissionRequester internal constructor(private val launch: (() -> Unit) -> Unit) {
    /** Esegue [onGranted] se il permesso c'è già o viene concesso; altrimenti guida l'utente. */
    fun withPermission(onGranted: () -> Unit) = launch(onGranted)
}

/**
 * Flusso permessi posizione:
 * 1. già concesso → azione immediata;
 * 2. serve spiegazione (rationale) → dialog esplicativo, poi richiesta di sistema;
 * 3. negato in modo permanente → dialog con scorciatoia alle impostazioni dell'app.
 */
@Composable
fun rememberLocationPermissionRequester(): LocationPermissionRequester {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    var dialog by remember { mutableStateOf<PermissionDialog?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = result.values.any { it }
        val action = pending
        pending = null
        if (granted) {
            action?.invoke()
        } else {
            val canAskAgain = activity?.let { act ->
                LOCATION_PERMISSIONS.any { act.shouldShowRequestPermissionRationale(it) }
            } ?: true
            dialog = if (canAskAgain) PermissionDialog.Denied else PermissionDialog.PermanentlyDenied
        }
    }

    when (dialog) {
        PermissionDialog.Rationale -> AlertDialog(
            onDismissRequest = { dialog = null; pending = null },
            title = { Text("Posizione dell'oggetto") },
            text = { Text("Object Finder usa il GPS solo quando registri o aggiorni un oggetto, per salvare dove si trova.") },
            confirmButton = {
                TextButton(onClick = { dialog = null; launcher.launch(LOCATION_PERMISSIONS) }) { Text("Continua") }
            },
            dismissButton = { TextButton(onClick = { dialog = null; pending = null }) { Text("Non ora") } },
        )
        PermissionDialog.Denied -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Permesso negato") },
            text = { Text("Senza permesso di posizione l'oggetto viene salvato senza coordinate. Puoi riprovare quando vuoi.") },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("OK") } },
        )
        PermissionDialog.PermanentlyDenied -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Posizione disattivata") },
            text = { Text("Il permesso di posizione è stato negato. Abilitalo dalle impostazioni dell'app per salvare le coordinate.") },
            confirmButton = {
                TextButton(onClick = { dialog = null; context.openAppSettings() }) { Text("Apri impostazioni") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Annulla") } },
        )
        null -> Unit
    }

    return remember(launcher, activity) {
        LocationPermissionRequester { onGranted ->
            when {
                hasLocationPermission(context) -> onGranted()
                activity != null && LOCATION_PERMISSIONS.any { activity.shouldShowRequestPermissionRationale(it) } -> {
                    pending = onGranted
                    dialog = PermissionDialog.Rationale
                }
                else -> {
                    pending = onGranted
                    launcher.launch(LOCATION_PERMISSIONS)
                }
            }
        }
    }
}

private enum class PermissionDialog { Rationale, Denied, PermanentlyDenied }

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

private fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}
