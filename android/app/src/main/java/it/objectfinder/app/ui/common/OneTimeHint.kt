package it.objectfinder.app.ui.common

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Suggerimento da mostrare una volta sola (per installazione): [shouldShow] finché non viene chiuso
 * o finché l'utente non usa la funzione che spiega. Salvato in locale, non sincronizzato.
 */
class OneTimeHint internal constructor(private val prefs: android.content.SharedPreferences, private val key: String) {
    private val seen = mutableStateOf(prefs.getBoolean(key, false))
    val shouldShow: Boolean get() = !seen.value
    fun dismiss() {
        if (seen.value) return
        seen.value = true
        prefs.edit().putBoolean(key, true).apply()
    }
}

@Composable
fun rememberOneTimeHint(key: String): OneTimeHint {
    val context = LocalContext.current
    return remember(key) { OneTimeHint(context.getSharedPreferences("hints", Context.MODE_PRIVATE), key) }
}
