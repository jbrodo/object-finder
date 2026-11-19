package it.objectfinder.app.data

import it.objectfinder.app.data.local.LOCAL_PROJECT_ID
import it.objectfinder.app.data.local.ObjectDao
import it.objectfinder.app.data.remote.ProjectDto
import it.objectfinder.app.data.remote.ProjectMemberDto
import it.objectfinder.app.data.remote.ProjectWithRoleDto
import it.objectfinder.app.data.remote.SupabaseException
import it.objectfinder.app.data.remote.SupabaseRestApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

data class ProjectMember(val userId: String, val email: String, val role: ProjectRole, val isMe: Boolean)

data class PendingInvite(val id: String, val email: String, val role: ProjectRole)

enum class InviteResult { ADDED, INVITED }

class ProjectRepository(
    private val api: SupabaseRestApi,
    private val auth: AuthRepository,
    private val settings: SettingsStore,
    private val dao: ObjectDao,
) {
    private val mutex = Mutex()
    private val _projects = MutableStateFlow<List<ActiveProject>>(emptyList())

    /** Ultimo elenco noto dei progetti dell'utente (con ruolo). */
    val projects: StateFlow<List<ActiveProject>> = _projects.asStateFlow()

    /** Id del progetto su cui leggere/scrivere: cloud se attivo, altrimenti progetto locale. */
    val currentProjectId: String get() = settings.activeProject.value?.id ?: LOCAL_PROJECT_ID

    /**
     * Bootstrap/refresh: mantiene il progetto attivo se ancora accessibile (aggiornandone nome e
     * ruolo), altrimenti sceglie il primo progetto modificabile, altrimenti ne crea uno di default.
     * Gli oggetti creati offline vengono riassegnati al progetto attivo se modificabile.
     */
    suspend fun ensureActiveProject(): ActiveProject = mutex.withLock {
        val userId = requireUserId()
        // Inviti ricevuti prima della registrazione: diventano membership (best-effort; richiede 007).
        runCatching { api.acceptMyInvites() }
        var list = fetchProjects(userId)
        val saved = settings.activeProject.value
        val chosen = list.firstOrNull { it.id == saved?.id }
            ?: list.firstOrNull { it.canEditObjects }
            ?: run {
                createRemote(DEFAULT_PROJECT_NAME, userId)
                list = fetchProjects(userId)
                list.first { it.role == ProjectRole.OWNER }
            }
        activate(chosen)
        chosen
    }

    suspend fun refreshProjects(): List<ActiveProject> = mutex.withLock { fetchProjects(requireUserId()) }

    suspend fun createProject(name: String): ActiveProject = mutex.withLock {
        val userId = requireUserId()
        val id = createRemote(name.trim().ifBlank { DEFAULT_PROJECT_NAME }, userId)
        val created = fetchProjects(userId).first { it.id == id }
        activate(created)
        created
    }

    suspend fun selectProject(project: ActiveProject) = activate(project)

    suspend fun listMembers(): List<ProjectMember> {
        val projectId = requireActiveCloudProject()
        val me = auth.session.value?.userId
        return api.listMembers(projectId).map { it.toMember(me) }
    }

    /** Cambio ruolo di un membro esistente. */
    suspend fun addMember(email: String, role: ProjectRole) {
        require(role in ProjectRole.assignable) { "Ruolo non assegnabile" }
        api.addMember(requireActiveCloudProject(), email.trim(), role.wire)
    }

    /** Aggiunge subito se l'account esiste, altrimenti crea un invito pendente. */
    suspend fun inviteMember(email: String, role: ProjectRole): InviteResult {
        require(role in ProjectRole.assignable) { "Ruolo non assegnabile" }
        return when (api.inviteMember(requireActiveCloudProject(), email.trim(), role.wire)) {
            "added" -> InviteResult.ADDED
            else -> InviteResult.INVITED
        }
    }

    suspend fun listPendingInvites(): List<PendingInvite> =
        api.listPendingInvites(requireActiveCloudProject()).map {
            PendingInvite(it.id, it.email, ProjectRole.fromWire(it.role) ?: ProjectRole.VIEWER)
        }

    suspend fun revokeInvite(id: String) = api.revokeInvite(id)

    suspend fun removeMember(userId: String) {
        api.removeMember(requireActiveCloudProject(), userId)
    }

    /** Esce dal progetto attivo e passa a un altro (o a uno nuovo di default). */
    suspend fun leaveActiveProject(): ActiveProject {
        val projectId = requireActiveCloudProject()
        api.removeMember(projectId, requireUserId())
        settings.setActiveProject(null)
        return ensureActiveProject()
    }

    private suspend fun activate(project: ActiveProject) {
        settings.setActiveProject(project)
        if (project.canEditObjects) dao.reassignProject(from = LOCAL_PROJECT_ID, to = project.id)
    }

    private suspend fun fetchProjects(userId: String): List<ActiveProject> =
        api.listProjects(userId).map { it.toActive() }.also { _projects.value = it }

    private suspend fun createRemote(name: String, userId: String): String {
        val id = UUID.randomUUID().toString()
        api.createProject(ProjectDto(id = id, name = name, ownerId = userId))
        return id
    }

    private fun requireUserId(): String = auth.session.value?.userId ?: throw SupabaseException.NotAuthenticated()

    private fun requireActiveCloudProject(): String {
        val active = settings.activeProject.value
        if (active == null || active.role == null) throw SupabaseException.NotAuthenticated()
        return active.id
    }

    companion object {
        const val DEFAULT_PROJECT_NAME = "I miei oggetti"
    }
}

internal fun ProjectWithRoleDto.toActive() = ActiveProject(
    id = id,
    name = name,
    role = ProjectRole.fromWire(memberships.firstOrNull()?.role) ?: ProjectRole.VIEWER,
)

internal fun ProjectMemberDto.toMember(me: String?) = ProjectMember(
    userId = userId,
    email = email ?: userId,
    role = ProjectRole.fromWire(role) ?: ProjectRole.VIEWER,
    isMe = userId == me,
)
