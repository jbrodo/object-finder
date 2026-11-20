package it.objectfinder.app.data

import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import it.objectfinder.app.data.local.LOCAL_PROJECT_ID
import it.objectfinder.app.data.remote.EmbeddingRowDto
import it.objectfinder.app.recognition.EmbeddingAccess
import it.objectfinder.app.recognition.SharedEmbeddings
import java.util.Base64
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
    val projectRepository = ProjectRepository(restApi, authRepository, settings, database.objectDao(), database.placeDao())
    val placeRepository = PlaceRepository(database.placeDao(), database.objectDao(), restApi, settings)
    val photoRepository = PhotoRepository(appContext, database.photoDao(), restApi)
    val identifierRepository = IdentifierRepository(database.identifierDao(), restApi)
    val locationHistoryRepository = LocationHistoryRepository(database.locationHistoryDao(), restApi)
    val inventoryExporter = InventoryExporter(
        appContext, database.objectDao(), database.identifierDao(), database.photoDao(), settings, database.placeDao(),
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
        places = placeRepository,
        placeDao = database.placeDao(),
    )

    /** Riconoscimento visivo on-device (MediaPipe): impronte delle foto e confronto. */
    val recognitionRepository = RecognitionRepository(
        ImageEmbedderEngine(appContext), database.photoEmbeddingDao(), database.objectDao(), settings,
        withTemporaryCopy = { photo, use -> photoRepository.withTemporaryCopy(photo, use) },
        shared = object : SharedEmbeddings {
            override suspend fun fetch(photoIds: List<String>, model: String) =
                restApi.listEmbeddings(photoIds, model).associate { it.photoId to Base64.getDecoder().decode(it.vector) }

            override suspend fun push(model: String, rows: Map<String, ByteArray>) =
                restApi.insertEmbeddings(rows.map { (id, v) -> EmbeddingRowDto(id, model, Base64.getEncoder().encodeToString(v)) })
        },
        access = {
            val project = settings.activeProject.value
            if (authRepository.session.value == null || project == null || project.id == LOCAL_PROJECT_ID) null
            else EmbeddingAccess(project.id, project.canEditObjects)
        },
    )

    /** Suggerimenti dalla foto (Cactus Mirage), solo su ARM e dopo il download del modello. */
    val mirageEngine = MirageEngine(appContext)

    /** Calcolo in background delle impronte mancanti (foto nuove, scaricate, cambio modello). */
    fun indexPhotosInBackground() {
        appScope.launch { runCatching { recognitionRepository.indexMissing() } }
    }

    init {
        // Dopo ogni sincronizzazione: foto nuove dal server (impronte da scaricare) o appena
        // caricate (impronte da inviare).
        appScope.launch {
            settings.syncState.filterIsInstance<SyncState.Done>().distinctUntilChanged().collect { indexPhotosInBackground() }
        }
    }
}
