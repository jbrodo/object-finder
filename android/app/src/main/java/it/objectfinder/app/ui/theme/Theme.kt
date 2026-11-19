package it.objectfinder.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF1F5F8B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDE5FF),
    onPrimaryContainer = Color(0xFF001D32),
    secondary = Color(0xFF52606D),
    tertiary = Color(0xFF8A5A00),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF94CCFF),
    onPrimary = Color(0xFF003352),
    primaryContainer = Color(0xFF004B74),
    onPrimaryContainer = Color(0xFFCDE5FF),
    secondary = Color(0xFFB9C8D8),
    tertiary = Color(0xFFFFB951),
)

@Composable
fun ObjectFinderTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
