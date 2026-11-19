package it.objectfinder.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import it.objectfinder.app.data.AppContainer
import it.objectfinder.app.ui.ObjectFinderApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = AppContainer(applicationContext)
        setContent {
            MaterialTheme {
                Surface {
                    ObjectFinderApp(container)
                }
            }
        }
    }
}
