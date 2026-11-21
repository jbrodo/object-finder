package it.objectfinder.app.ui

import it.objectfinder.app.ui.objects.WhereEditor
import it.objectfinder.app.ui.objects.SearchAndFilters
import it.objectfinder.app.ui.objects.PlantUi
import it.objectfinder.app.ui.objects.ObjectFilter
import it.objectfinder.app.ui.objects.MirageUi
import it.objectfinder.app.ui.objects.MirageCard
import it.objectfinder.app.recognition.ReadText
import it.objectfinder.app.recognition.MirageSuggestions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.fillMaxWidth
import it.objectfinder.app.ui.common.rememberAppMessages
import it.objectfinder.app.ui.common.LocalAppMessages
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.SnackbarHostState
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import it.objectfinder.app.data.SyncMerge
import it.objectfinder.app.data.local.LocationHistoryEntity
import it.objectfinder.app.data.local.ObjectEntity
import it.objectfinder.app.data.local.SyncStatus
import it.objectfinder.app.location.DeviceLocation
import it.objectfinder.app.ui.common.readableWidth
import it.objectfinder.app.ui.objects.ConflictBanner
import it.objectfinder.app.ui.objects.ConflictUi
import it.objectfinder.app.ui.objects.EditorState
import it.objectfinder.app.ui.objects.EmptyState
import it.objectfinder.app.ui.objects.LoanPanel
import it.objectfinder.app.ui.objects.ObjectGrid
import it.objectfinder.app.ui.objects.WherePanel
import it.objectfinder.app.ui.theme.ObjectFinderTheme
import java.time.LocalDate

/*
 * Preview dei componenti chiave e delle due schermate principali con dati di esempio.
 * Le stesse funzioni sono catturate come PNG da PreviewScreenshotTest (Roborazzi, su JVM):
 * telefono, pieghevole e tablet, tema chiaro e scuro. Date relative a oggi: i PNG restano stabili.
 */

private const val PHONE = "spec:width=411dp,height=891dp"
private const val FOLDABLE = "spec:width=673dp,height=841dp"
private const val TABLET = "spec:width=1280dp,height=800dp,dpi=240"

private const val HOME_LAT = 45.4642
private const val HOME_LNG = 9.1900

private fun sample(
    id: String,
    name: String,
    place: String,
    lat: Double? = HOME_LAT,
    accuracy: Float? = 6f,
    loanedTo: String = "",
    loanDue: LocalDate? = null,
    notes: String = "",
    sync: SyncStatus = SyncStatus.SYNCED,
) = ObjectEntity(
    id = id,
    projectId = "preview",
    name = name,
    category = "",
    notes = notes,
    place = place,
    latitude = lat,
    longitude = lat?.let { HOME_LNG },
    accuracyMeters = accuracy,
    loanedTo = loanedTo,
    loanDue = loanDue?.toString(),
    syncStatus = sync.wire,
)

internal val SampleObjects: List<ObjectEntity>
    get() {
        val today = LocalDate.now()
        return listOf(
            sample("1", "Trapano a percussione", "scaffale in alto a sinistra"),
            sample("2", "Tenda da campeggio 3 posti", "armadio del corridoio, ripiano basso", accuracy = 48f),
            sample("3", "Scala telescopica", "", loanedTo = "Marco", loanDue = today.plusDays(5)),
            sample("4", "Cassetta degli attrezzi rossa", "sotto il banco da lavoro", sync = SyncStatus.CONFLICT),
            sample("5", "Passaporto", "cassetto della scrivania", lat = null, accuracy = null, notes = "Scade a marzo"),
            sample("6", "Bici da corsa", "cantina, box 12", loanedTo = "Giulia", loanDue = today.minusDays(3)),
        )
    }

private val SamplePaths = mapOf("1" to "Casa › Garage", "2" to "Casa", "4" to "Casa › Garage", "6" to "Condominio")

private val SampleWhere: EditorState
    get() = EditorState(
        isNew = false,
        loaded = true,
        name = "Trapano a percussione",
        place = "scaffale in alto a sinistra",
        latitude = HOME_LAT,
        longitude = HOME_LNG,
        accuracyMeters = 6f,
        origin = DeviceLocation(HOME_LAT + 0.0011, HOME_LNG, 12f),
        loanedTo = "Marco",
        loanDue = LocalDate.now().plusDays(5),
    )

private val SampleHistory: List<LocationHistoryEntity>
    get() = listOf(
        LocationHistoryEntity(
            id = "h1", objectId = "1", latitude = HOME_LAT, longitude = HOME_LNG, accuracyMeters = 6f,
            recordedAt = System.currentTimeMillis() - 3L * 24 * 60 * 60 * 1000,
        ),
    )

@Composable
private fun SampleTheme(content: @Composable () -> Unit) = ObjectFinderTheme(dynamicColor = false) {
    val messages = rememberAppMessages(remember { SnackbarHostState() })
    CompositionLocalProvider(LocalAppMessages provides messages) { Surface(content = content) }
}

@Composable
private fun SampleGrid(modifier: Modifier = Modifier) = ObjectGrid(
    items = SampleObjects,
    cover = { null },
    distance = { if (it.hasLocation) 120.0 else null },
    placePath = { SamplePaths[it.id].orEmpty() },
    showSyncState = true,
    isSelected = { it.id == "2" },
    onClick = {},
    onLongClick = {},
    modifier = modifier,
)

// --- Schermate ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Preview(name = "Oggetti · telefono", device = PHONE)
@Preview(name = "Oggetti · telefono scuro", device = PHONE, uiMode = UI_MODE_NIGHT_YES)
@Preview(name = "Oggetti · pieghevole", device = FOLDABLE)
@Preview(name = "Oggetti · tablet", device = TABLET)
@Composable
internal fun ObjectsScreenPreview() = SampleTheme {
    AppNavigationSuite(current = Tab.OBJECTS, onSelect = {}) {
        Scaffold(topBar = { TopAppBar(title = { Text("Casa") }) }) { padding ->
            Column(Modifier.padding(padding)) {
                SearchAndFilters(
                    query = "", onQueryChange = {}, nearby = false, locating = false, onNearby = {},
                    filter = ObjectFilter.ACTIVE, onFilter = {}, placeLabel = "Luogo", placeSelected = false,
                    onPlace = {}, onScan = {}, onRecognize = {},
                )
                SampleGrid()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(name = "Scheda · telefono", device = PHONE)
@Preview(name = "Scheda · telefono scuro", device = PHONE, uiMode = UI_MODE_NIGHT_YES)
@Preview(name = "Scheda · tablet", device = TABLET)
@Composable
internal fun ObjectPageLayoutPreview() = SampleTheme {
    Scaffold(topBar = { TopAppBar(title = { Text("Scheda oggetto") }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).readableWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                SampleWhere.name,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 4.dp).semantics { heading() },
            )
            WherePanel(
                state = SampleWhere, history = SampleHistory, readOnly = false,
                onMoveHere = {}, onPickOnMap = {}, onUseBestNow = {}, onDescribe = {},
                placePath = "Casa › Garage",
            )
            LoanPanel(
                loanedTo = "Marco", loanedAt = null, due = SampleWhere.loanDue,
                readOnly = false, onReturned = {}, onEdit = {},
            )
        }
    }
}

// --- Componenti --------------------------------------------------------------------------

@Preview(name = "Dove si trova", widthDp = 411)
@Preview(name = "Dove si trova · scuro", widthDp = 411, uiMode = UI_MODE_NIGHT_YES)
@Preview(name = "Dove si trova · testo 200%", widthDp = 411, fontScale = 2f)
@Composable
internal fun WherePanelPreview() = SampleTheme {
    Column(Modifier.padding(16.dp)) {
        WherePanel(
            state = SampleWhere, history = SampleHistory, readOnly = false,
            onMoveHere = {}, onPickOnMap = {}, onUseBestNow = {}, onDescribe = {},
            placePath = "Casa › Garage",
        )
    }
}

@Preview(name = "Prestito", widthDp = 411)
@Preview(name = "Prestito · scuro", widthDp = 411, uiMode = UI_MODE_NIGHT_YES)
@Composable
internal fun LoanPanelPreview() = SampleTheme {
    val today = LocalDate.now()
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LoanPanel("Marco", null, today.plusDays(5), readOnly = false, onReturned = {}, onEdit = {})
        LoanPanel("Giulia", null, today.minusDays(3), readOnly = false, onReturned = {}, onEdit = {})
    }
}

@Preview(name = "Conflitto", widthDp = 411)
@Preview(name = "Conflitto · scuro", widthDp = 411, uiMode = UI_MODE_NIGHT_YES)
@Composable
internal fun ConflictBannerPreview() = SampleTheme {
    Column(Modifier.padding(16.dp)) {
        ConflictBanner(
            conflict = ConflictUi(
                deletedOnServer = false,
                differences = listOf(
                    SyncMerge.FieldDiff("Dove si trova", "scaffale in alto a sinistra", "banco da lavoro"),
                    SyncMerge.FieldDiff("Note", "punta da 8 nella custodia", "—"),
                ),
            ),
            readOnly = false,
            onKeepMine = {},
            onUseServer = {},
        )
    }
}

@Preview(name = "Elenco vuoto", widthDp = 411, heightDp = 480)
@Composable
internal fun EmptyStatePreview() = SampleTheme {
    EmptyState(
        title = "Nessun oggetto registrato",
        body = "Registra il primo oggetto: ne salveremo la posizione GPS per ritrovarlo sulla mappa.",
        icon = Icons.Outlined.Inventory2,
        actionLabel = "Registra oggetto",
    )
}

@Preview(name = "Nessun risultato", widthDp = 411, heightDp = 420)
@Composable
internal fun NoResultsPreview() = SampleTheme {
    EmptyState(
        title = "Nessun risultato",
        body = "Nessun oggetto corrisponde a ricerca e filtro selezionati.",
        icon = Icons.Outlined.SearchOff,
        actionLabel = "Azzera ricerca e filtri",
        primary = false,
    )
}

// --- Modulo di registrazione -------------------------------------------------------------

private val SampleDraft: EditorState
    get() = EditorState(
        isNew = true, loaded = true, name = "Trapano a percussione", nameTouched = true,
        latitude = HOME_LAT, longitude = HOME_LNG, accuracyMeters = 6f,
    )

@Preview(name = "Registrazione", widthDp = 411, heightDp = 1180)
@Preview(name = "Registrazione · scuro", widthDp = 411, heightDp = 1180, uiMode = UI_MODE_NIGHT_YES)
@Composable
internal fun RegistrationFormPreview() = SampleTheme {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = SampleDraft.name, onValueChange = {}, label = { Text("Nome oggetto *") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        MirageCard(
            state = MirageUi.Ready(
                MirageSuggestions(
                    names = listOf("Trapano a percussione", "Trapano avvitatore"),
                    texts = listOf(ReadText("BOSCH PSB 500 RE", codeLike = true), ReadText("230 V", codeLike = false)),
                ),
            ),
            onDownload = {}, onRetry = {}, onAnalyze = {}, onUseName = {}, onAddToNotes = {}, onLinkAsCode = {},
            plant = PlantUi.Idle,
        )
        WhereEditor(
            placePath = "Casa › Garage", onPickPlace = {}, onPlaceChange = {}, state = SampleDraft.copy(placeId = "garage", place = "scaffale in alto a sinistra"),
            onCapture = {}, onPickOnMap = {}, onUseBestNow = {}, onClear = {},
        )
    }
}
