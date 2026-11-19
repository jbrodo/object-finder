package it.objectfinder.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import it.objectfinder.app.ui.ObjectFinderApp
import it.objectfinder.app.ui.theme.ObjectFinderTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as ObjectFinderApplication).container
        setContent {
            ObjectFinderTheme {
                ObjectFinderApp(container)
            }
        }
    }
}
