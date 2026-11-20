package it.objectfinder.app.data

import android.content.Context
import it.objectfinder.app.export.InventoryExporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import it.objectfinder.app.recognition.ImageEmbedderEngine
import it.objectfinder.app.recognition.MirageEngine
import it.objectfinder.app.recognition.RecognitionRepository
import it.objectfinder.app.data.local.ObjectDatabase
import it.objectfinder.app.data.remote.AppConfig
import it.objectfinder.app.data.remote.SupabaseAuthApi
import it.objectfinder.app.data.remote.SupabaseHttp
import it.objectfinder.app.data.remote.SupabaseRestApi

/** Dependency container manuale: creato una sola volta in [it.objectfinder.app.ObjectFinderApplication]. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val config = AppConfig.load(appContext)
    private val database = ObjectDatabase.create(appContext)
    private val http = SupabaseHttp(config)

    /** Scope di processo per lavori che devono sopravvivere a una schermata (es. pulizia file). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val settings = SettingsStore(appContext)
    val sessionStore = SessionStore(appContext)

    val authRepository = AuthRepository(SupabaseAuthApi(http), sessionStore, settings)
    private val restApi = SupabaseRestApi(http, authRepository)
    val projectRepository = ProjectRepository(restApi, authRepository, settings, database.objectDao())
    val photoRepository = PhotoRepository(appContext, database.photoDao(), restApi)
    val identifierRepository = IdentifierRepository(database.identifierDao(), restApi)
    val locationHistoryRepository = LocationHistoryRepository(database.locationHistoryDao(), restApi)
    val inventoryExporter = InventoryExporter(
        appContext, database.objectDao(), database.identifierDao(), database.photoDao(), settings,
    )
    val objectRepository = ObjectRepository(
        dao = database.objectDao(),
        api = restApi,
        auth = authRepository,
        projects = projectRepository,
        settings = settings,
        photos = photoRepository,
        identifiers = identifierRepository,
        history = locationHistoryRepository,
        conflicts = database.conflictDao(),
    )

    /** Riconoscimento visivo on-device (MediaPipe): impronte delle foto e confronto. */
    val recognitionRepository = RecognitionRepository(
        ImageEmbedderEngine(appContext), database.photoEmbeddingDao(), database.objectDao(), settings,
        withTemporaryCopy = { photo, use -> photoRepository.withTemporaryCopy(photo, use) },
    )

    /** Suggerimenti dalla foto (Cactus Mirage), solo su ARM e dopo il download del modello. */
    val mirageEngine = MirageEngine(appContext)

    /** Calcolo in background delle impronte mancanti (foto nuove, scaricate, cambio modello). */
    fun indexPhotosInBackground() {
        appScope.launch { runCatching { recognitionRepository.indexMissing() } }
    }
}
