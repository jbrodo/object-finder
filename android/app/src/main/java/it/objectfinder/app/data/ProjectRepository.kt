package it.objectfinder.app.data

import it.objectfinder.app.data.local.LOCAL_PROJECT_ID
import it.objectfinder.app.data.local.ObjectDao
import it.objectfinder.app.data.remote.ProjectDto
import it.objectfinder.app.data.remote.SupabaseException
import it.objectfinder.app.data.remote.SupabaseRestApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class ProjectRepository(
    private val api: SupabaseRestApi,
    private val auth: AuthRepository,
    private val settings: SettingsStore,
    private val dao: ObjectDao,
) {
    private val mutex = Mutex()

    /** Id del progetto su cui leggere/scrivere: cloud se attivo, altrimenti progetto locale. */
    val currentProjectId: String get() = settings.activeProject.value?.id ?: LOCAL_PROJECT_ID

    /**
     * Bootstrap dopo login: usa il progetto attivo se ancora accessibile, altrimenti il primo
     * progetto dell'utente, altrimenti ne crea uno di default. Gli oggetti creati offline
     * vengono poi riassegnati al progetto cloud.
     */
    suspend fun ensureActiveProject(): ActiveProject = mutex.withLock {
        val session = auth.session.value ?: throw SupabaseException.NotAuthenticated()
        val projects = api.listProjects()
        val saved = settings.activeProject.value
        val chosen = projects.firstOrNull { it.id == saved?.id }
            ?: projects.firstOrNull()
            ?: createDefault(session.userId)
        val active = ActiveProject(chosen.id, chosen.name)
        settings.setActiveProject(active)
        dao.reassignProject(from = LOCAL_PROJECT_ID, to = active.id)
        active
    }

    private suspend fun createDefault(userId: String): ProjectDto {
        val project = ProjectDto(
            id = UUID.randomUUID().toString(),
            name = "I miei oggetti",
            ownerId = userId,
        )
        api.createProject(project)
        return project
    }
}
