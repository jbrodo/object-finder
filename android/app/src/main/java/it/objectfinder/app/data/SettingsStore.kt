package it.objectfinder.app.data

import android.content.Context
import it.objectfinder.app.maps.MapEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ActiveProject(val id: String, val name: String)

sealed interface SyncState {
    data object Idle : SyncState
    data object Running : SyncState
    data class Done(val atMillis: Long, val pushed: Int, val pulled: Int) : SyncState
    data class Failed(val atMillis: Long, val message: String) : SyncState
}

/** Preferenze applicative non sensibili + stato osservabile di sync. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _mapEngine = MutableStateFlow(
        runCatching { MapEngine.valueOf(prefs.getString(KEY_MAP, null) ?: "") }.getOrDefault(MapEngine.GOOGLE_MAPS)
    )
    val mapEngine: StateFlow<MapEngine> = _mapEngine.asStateFlow()

    private val _activeProject = MutableStateFlow(readProject())
    val activeProject: StateFlow<ActiveProject?> = _activeProject.asStateFlow()

    private val _onboardingDone = MutableStateFlow(prefs.getBoolean(KEY_ONBOARDING, false))
    val onboardingDone: StateFlow<Boolean> = _onboardingDone.asStateFlow()

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    fun setMapEngine(engine: MapEngine) {
        prefs.edit().putString(KEY_MAP, engine.name).apply()
        _mapEngine.value = engine
    }

    fun setActiveProject(project: ActiveProject?) {
        prefs.edit().apply {
            if (project == null) remove(KEY_PROJECT_ID).remove(KEY_PROJECT_NAME)
            else putString(KEY_PROJECT_ID, project.id).putString(KEY_PROJECT_NAME, project.name)
        }.apply()
        _activeProject.value = project
    }

    fun setOnboardingDone() {
        prefs.edit().putBoolean(KEY_ONBOARDING, true).apply()
        _onboardingDone.value = true
    }

    fun pullCursor(projectId: String): String? = prefs.getString("$KEY_CURSOR$projectId", null)

    fun setPullCursor(projectId: String, cursor: String?) {
        prefs.edit().putString("$KEY_CURSOR$projectId", cursor).apply()
    }

    fun clearAccountData() {
        prefs.edit().apply {
            prefs.all.keys.filter { it.startsWith(KEY_CURSOR) }.forEach { remove(it) }
        }.apply()
        setActiveProject(null)
        _syncState.value = SyncState.Idle
    }

    fun setSyncState(state: SyncState) {
        _syncState.value = state
    }

    private fun readProject(): ActiveProject? {
        val id = prefs.getString(KEY_PROJECT_ID, null) ?: return null
        return ActiveProject(id, prefs.getString(KEY_PROJECT_NAME, null) ?: "Progetto")
    }

    private companion object {
        const val KEY_MAP = "map_engine"
        const val KEY_PROJECT_ID = "active_project_id"
        const val KEY_PROJECT_NAME = "active_project_name"
        const val KEY_ONBOARDING = "onboarding_done"
        const val KEY_CURSOR = "pull_cursor_"
    }
}
