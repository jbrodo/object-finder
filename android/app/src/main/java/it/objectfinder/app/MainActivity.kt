package it.objectfinder.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.objectfinder.app.ui.ObjectFinderApp
import it.objectfinder.app.ui.theme.ObjectFinderTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    /** Ultimo deep link (etichetta QR) non ancora gestito dalla UI. */
    private val pendingLink = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) pendingLink.value = intent.viewData()
        val container = (application as ObjectFinderApplication).container
        setContent {
            val link by pendingLink.collectAsStateWithLifecycle()
            ObjectFinderTheme {
                ObjectFinderApp(container, pendingLink = link, onLinkHandled = { pendingLink.value = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.viewData()?.let { pendingLink.value = it }
    }

    private fun Intent?.viewData(): String? = this?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString
}
