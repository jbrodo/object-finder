package it.objectfinder.app.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
}
