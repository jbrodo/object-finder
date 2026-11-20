package it.objectfinder.app.data

import android.content.Context
import it.objectfinder.app.maps.MapEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Ruoli allineati all'enum `public.project_role`. */
enum class ProjectRole(val wire: String, val label: String) {
    OWNER("owner", "Proprietario"),
    ADMIN("admin", "Amministratore"),
    EDITOR("editor", "Editor"),
    VIEWER("viewer", "Solo lettura"),
    TRACKER("tracker", "Tracker");

    val canEditObjects: Boolean get() = this == OWNER || this == ADMIN || this == EDITOR
    val canManageMembers: Boolean get() = this == OWNER || this == ADMIN
    /** RLS objects_delete: solo owner/admin. */
    val canDeleteObjects: Boolean get() = this == OWNER || this == ADMIN

    companion object {
        fun fromWire(value: String?): ProjectRole? = entries.firstOrNull { it.wire == value }
        /** Ruoli assegnabili a un membro (l'owner è unico e fissato alla creazione). */
        val assignable = listOf(ADMIN, EDITOR, VIEWER)
    }
}

/** Progetto attivo; [role] null = progetto locale offline (nessuna restrizione). */
data class ActiveProject(val id: String, val name: String, val role: ProjectRole? = null) {
    val canEditObjects: Boolean get() = role?.canEditObjects ?: true
    val canManageMembers: Boolean get() = role?.canManageMembers ?: false
    /** Progetto locale (offline): l'utente è l'unico proprietario dei suoi dati. */
    val canDeleteObjects: Boolean get() = role?.canDeleteObjects ?: true
}

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
            if (project == null) remove(KEY_PROJECT_ID).remove(KEY_PROJECT_NAME).remove(KEY_PROJECT_ROLE)
            else putString(KEY_PROJECT_ID, project.id)
                .putString(KEY_PROJECT_NAME, project.name)
                .putString(KEY_PROJECT_ROLE, project.role?.wire)
        }.apply()
        _activeProject.value = project
    }

    fun pkceVerifier(): String? = prefs.getString(KEY_PKCE, null)

    fun pkceFlow(): String? = prefs.getString(KEY_PKCE_FLOW, null)

    /** Verifier PKCE e tipo di link atteso (registrazione o ripristino password). */
    fun setPkceVerifier(value: String?, flow: String?) {
        prefs.edit().apply {
            if (value == null) remove(KEY_PKCE) else putString(KEY_PKCE, value)
            if (flow == null) remove(KEY_PKCE_FLOW) else putString(KEY_PKCE_FLOW, flow)
        }.apply()
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
        return ActiveProject(
            id = id,
            name = prefs.getString(KEY_PROJECT_NAME, null) ?: "Progetto",
            role = ProjectRole.fromWire(prefs.getString(KEY_PROJECT_ROLE, null)),
        )
    }

    private companion object {
        const val KEY_MAP = "map_engine"
        const val KEY_PROJECT_ID = "active_project_id"
        const val KEY_PROJECT_NAME = "active_project_name"
        const val KEY_PROJECT_ROLE = "active_project_role"
        const val KEY_ONBOARDING = "onboarding_done"
        const val KEY_PKCE = "pkce_verifier"
        const val KEY_PKCE_FLOW = "pkce_flow"
        const val KEY_CURSOR = "pull_cursor_"
    }
}
