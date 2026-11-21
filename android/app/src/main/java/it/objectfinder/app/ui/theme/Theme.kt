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

// Schema di riserva (Android 11 e precedenti): da Android 12 i colori vengono dallo sfondo.
// Toni derivati da primary/secondary/tertiary come nel Material Theme Builder.
private val LightColors = lightColorScheme(
    primary = Color(0xFF1F5F8B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDE5FF),
    onPrimaryContainer = Color(0xFF001D32),
    secondary = Color(0xFF52606D),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD5E4F4),
    onSecondaryContainer = Color(0xFF0F1D28),
    tertiary = Color(0xFF8A5A00),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDDB7),
    onTertiaryContainer = Color(0xFF271901),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    // Neutri tinti dal blu primario: niente lavanda del baseline Material.
    background = Color(0xFFF6FAFF),
    onBackground = Color(0xFF181C21),
    surface = Color(0xFFF6FAFF),
    onSurface = Color(0xFF181C21),
    surfaceVariant = Color(0xFFD8E4F1),
    onSurfaceVariant = Color(0xFF3E4853),
    surfaceTint = Color(0xFF1F5F8B),
    outline = Color(0xFF6E7884),
    outlineVariant = Color(0xFFBDC8D5),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEFF4FB),
    surfaceContainer = Color(0xFFE9EEF5),
    surfaceContainerHigh = Color(0xFFE3E9F0),
    surfaceContainerHighest = Color(0xFFDEE3EA),
    surfaceDim = Color(0xFFD5DAE1),
    surfaceBright = Color(0xFFF6FAFF),
    inverseSurface = Color(0xFF2C3136),
    inverseOnSurface = Color(0xFFECF1F8),
    inversePrimary = Color(0xFF94CCFF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF94CCFF),
    onPrimary = Color(0xFF003352),
    primaryContainer = Color(0xFF004B74),
    onPrimaryContainer = Color(0xFFCDE5FF),
    secondary = Color(0xFFB9C8D8),
    onSecondary = Color(0xFF24323D),
    secondaryContainer = Color(0xFF3B4855),
    onSecondaryContainer = Color(0xFFD5E4F4),
    tertiary = Color(0xFFFFB951),
    onTertiary = Color(0xFF442B00),
    tertiaryContainer = Color(0xFF633F00),
    onTertiaryContainer = Color(0xFFFFDDB7),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0F1419),
    onBackground = Color(0xFFDEE3EA),
    surface = Color(0xFF0F1419),
    onSurface = Color(0xFFDEE3EA),
    surfaceVariant = Color(0xFF3E4853),
    onSurfaceVariant = Color(0xFFBDC8D5),
    surfaceTint = Color(0xFF94CCFF),
    outline = Color(0xFF87929E),
    outlineVariant = Color(0xFF3E4853),
    surfaceContainerLowest = Color(0xFF090F15),
    surfaceContainerLow = Color(0xFF181C21),
    surfaceContainer = Color(0xFF1C2025),
    surfaceContainerHigh = Color(0xFF262A2F),
    surfaceContainerHighest = Color(0xFF31353A),
    surfaceDim = Color(0xFF0F1419),
    surfaceBright = Color(0xFF353A3F),
    inverseSurface = Color(0xFFDEE3EA),
    inverseOnSurface = Color(0xFF2C3136),
    inversePrimary = Color(0xFF1F5F8B),
)

@Composable
fun ObjectFinderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // false nelle preview/screenshot: mostra lo schema di riserva invece dei colori dello sfondo.
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
