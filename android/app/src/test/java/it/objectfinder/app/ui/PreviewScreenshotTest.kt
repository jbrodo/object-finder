package it.objectfinder.app.ui

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Cattura le preview di Previews.kt come PNG in app/src/test/screenshots.
 *  - ./gradlew :app:recordRoborazziDebug  → aggiorna i PNG di riferimento
 *  - ./gradlew :app:verifyRoborazziDebug  → fallisce se la UI cambia rispetto ai PNG
 * Application vuota: l'avvio reale (Room, modelli, WorkManager) non serve a disegnare la UI.
 */
private const val Q_PHONE = "w411dp-h891dp-port-xxhdpi"
private const val Q_FOLDABLE = "w673dp-h841dp-port-xhdpi"
private const val Q_TABLET = "w1280dp-h800dp-land-hdpi"

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = Q_PHONE)
class PreviewScreenshotTest {

    private fun shot(name: String, content: @Composable () -> Unit) =
        captureRoboImage("src/test/screenshots/$name.png") { content() }

    @Test fun objectsPhone() = shot("objects_phone") { ObjectsScreenPreview() }

    @Config(qualifiers = "+night")
    @Test fun objectsPhoneDark() = shot("objects_phone_dark") { ObjectsScreenPreview() }

    @Config(qualifiers = Q_FOLDABLE)
    @Test fun objectsFoldable() = shot("objects_foldable") { ObjectsScreenPreview() }

    @Config(qualifiers = Q_TABLET)
    @Test fun objectsTablet() = shot("objects_tablet") { ObjectsScreenPreview() }

    @Test fun objectPagePhone() = shot("object_page_phone") { ObjectPageLayoutPreview() }

    @Config(qualifiers = "+night")
    @Test fun objectPagePhoneDark() = shot("object_page_phone_dark") { ObjectPageLayoutPreview() }

    @Config(qualifiers = Q_TABLET)
    @Test fun objectPageTablet() = shot("object_page_tablet") { ObjectPageLayoutPreview() }

    @Test fun wherePanelLargeText() = shot("where_panel_font200") {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
            WherePanelPreview()
        }
    }

    @Test fun loanPanels() = shot("loan_panels") { LoanPanelPreview() }

    @Config(qualifiers = "+night")
    @Test fun loanPanelsDark() = shot("loan_panels_dark") { LoanPanelPreview() }

    @Test fun conflictBanner() = shot("conflict_banner") { ConflictBannerPreview() }

    @Test fun emptyState() = shot("empty_state") { EmptyStatePreview() }
}
