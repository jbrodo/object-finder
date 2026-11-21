package it.objectfinder.app.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Messaggi brevi dell'app, al posto dei Toast: una Snackbar sola, con un'azione quando serve
 * ("Impostazioni", "Annulla"). Un messaggio nuovo sostituisce quello in corso invece di accodarsi.
 * Dentro dialog, fogli e fotocamera il messaggio va mostrato nel componente stesso: lì una
 * Snackbar resterebbe sotto lo scrim.
 */
@Stable
class AppMessages(private val host: SnackbarHostState, private val scope: CoroutineScope) {
    fun show(text: String, actionLabel: String? = null, onAction: () -> Unit = {}) {
        scope.launch {
            host.currentSnackbarData?.dismiss()
            val result = host.showSnackbar(
                message = text,
                actionLabel = actionLabel,
                // Con un'azione serve il tempo di leggerla e toccarla.
                duration = if (actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) onAction()
        }
    }
}

val LocalAppMessages = staticCompositionLocalOf<AppMessages> {
    error("AppMessages non fornito: avvolgi la schermata in CompositionLocalProvider(LocalAppMessages provides …)")
}

@Composable
fun rememberAppMessages(host: SnackbarHostState): AppMessages {
    val scope = rememberCoroutineScope()
    return remember(host, scope) { AppMessages(host, scope) }
}

/** "Posizione non disponibile" con il rimedio a portata di dito: le impostazioni di localizzazione. */
fun AppMessages.locationUnavailable(context: Context) =
    show("Posizione non disponibile: verifica che il GPS sia attivo", actionLabel = "Impostazioni") {
        try {
            context.startActivity(
                Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: ActivityNotFoundException) {
            // Nessuna schermata di sistema dedicata: il messaggio ha già detto cosa fare.
        }
    }
