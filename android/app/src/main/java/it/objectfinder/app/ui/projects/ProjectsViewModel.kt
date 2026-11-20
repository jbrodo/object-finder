package it.objectfinder.app.ui.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.objectfinder.app.data.ActiveProject
import it.objectfinder.app.data.InviteEmail
import it.objectfinder.app.data.InviteResult
import it.objectfinder.app.data.PendingInvite
import it.objectfinder.app.data.ProjectMember
import it.objectfinder.app.data.ProjectRepository
import it.objectfinder.app.data.ProjectRole
import it.objectfinder.app.data.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Stato comune: operazione in corso + messaggio da mostrare (errore o conferma). */
data class OpState(val busy: Boolean = false, val message: String? = null)

private fun Throwable.userMessage() = message ?: javaClass.simpleName

data class ProjectsUiState(
    val projects: List<ActiveProject> = emptyList(),
    val activeId: String? = null,
    val op: OpState = OpState(),
)

class ProjectsViewModel(
    private val repository: ProjectRepository,
    settings: SettingsStore,
    private val onProjectChanged: () -> Unit,
) : ViewModel() {
    private val op = MutableStateFlow(OpState())

    val state: StateFlow<ProjectsUiState> =
        combine(repository.projects, settings.activeProject, op) { list, active, o ->
            ProjectsUiState(list, active?.id, o)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProjectsUiState())

    init { refresh() }

    fun refresh() = run { repository.refreshProjects() }

    fun select(project: ActiveProject) = run(done = "Progetto attivo: ${project.name}") {
        repository.selectProject(project)
        onProjectChanged()
    }

    fun create(name: String) = run(done = "Progetto creato") {
        repository.createProject(name)
        onProjectChanged()
    }

    fun consumeMessage() = op.update { it.copy(message = null) }

    private fun run(done: String? = null, block: suspend () -> Unit) {
        if (op.value.busy) return
        op.value = OpState(busy = true)
        viewModelScope.launch {
            op.value = runCatching { block() }
                .fold({ OpState(message = done) }, { OpState(message = it.userMessage()) })
        }
    }
}

data class MembersUiState(
    val members: List<ProjectMember> = emptyList(),
    val invites: List<PendingInvite> = emptyList(),
    /** Invito appena creato: la UI propone di condividerlo. */
    val justInvited: PendingInvite? = null,
    /** Esito dell'email automatica per [justInvited] (null finché non è noto). */
    val inviteEmail: InviteEmail? = null,
    val loaded: Boolean = false,
    val op: OpState = OpState(),
    /** true dopo l'uscita dal progetto: la schermata si chiude. */
    val left: Boolean = false,
)

class MembersViewModel(
    private val repository: ProjectRepository,
    private val onProjectChanged: () -> Unit,
) : ViewModel() {
    private val _state = MutableStateFlow(MembersUiState())
    val state: StateFlow<MembersUiState> = _state.asStateFlow()

    init { reload() }

    fun reload() = run { load() }

    fun add(email: String, role: ProjectRole) = run {
        val result = repository.inviteMember(email, role)
        load()
        if (result == InviteResult.INVITED) {
            val invite = _state.value.invites.firstOrNull { it.email.equals(email.trim(), ignoreCase = true) }
                ?: PendingInvite("", email.trim().lowercase(), role)
            // Email automatica; se il server non può mandarla resta la condivisione manuale.
            val mail = repository.sendInviteEmail(invite.email)
            _state.update { it.copy(justInvited = invite, inviteEmail = mail) }
        } else {
            _state.update { it.copy(op = it.op.copy(message = "Membro aggiunto")) }
        }
    }

    fun revokeInvite(invite: PendingInvite) = run(done = "Invito revocato") {
        repository.revokeInvite(invite.id)
        load()
    }

    fun shareInvite(invite: PendingInvite) = _state.update { it.copy(justInvited = invite, inviteEmail = null) }

    fun consumeInvite() = _state.update { it.copy(justInvited = null, inviteEmail = null) }

    /** Reinvio dell'email per un invito in attesa (il server limita i reinvii ravvicinati). */
    fun emailInvite(invite: PendingInvite) = run {
        val text = when (val mail = repository.sendInviteEmail(invite.email)) {
            InviteEmail.Sent -> "Email di invito inviata a ${invite.email}"
            InviteEmail.NotConfigured -> "Invio email non attivo sul server: usa Condividi invito"
            is InviteEmail.NotSent -> "Email non inviata: ${mail.reason}"
        }
        _state.update { it.copy(op = it.op.copy(message = text)) }
    }

    fun changeRole(member: ProjectMember, role: ProjectRole) = run(done = "Ruolo aggiornato") {
        repository.addMember(member.email, role)
        load()
    }

    fun remove(member: ProjectMember) = run(done = "Membro rimosso") {
        repository.removeMember(member.userId)
        load()
    }

    fun leave() = run {
        repository.leaveActiveProject()
        onProjectChanged()
        _state.update { it.copy(left = true) }
    }

    fun consumeMessage() = _state.update { it.copy(op = it.op.copy(message = null)) }

    private suspend fun load() {
        val members = repository.listMembers()
        // Gli inviti sono visibili solo a owner/admin (RLS): per gli altri l'elenco è vuoto.
        val invites = runCatching { repository.listPendingInvites() }.getOrDefault(emptyList())
        _state.update { it.copy(members = members, invites = invites, loaded = true) }
    }

    private fun run(done: String? = null, block: suspend () -> Unit) {
        if (_state.value.op.busy) return
        _state.update { it.copy(op = OpState(busy = true)) }
        viewModelScope.launch {
            val result = runCatching { block() }
            _state.update { current ->
                current.copy(
                    loaded = true,
                    op = result.fold(
                        // Un messaggio impostato dal blocco stesso (es. "Membro aggiunto") non va perso.
                        onSuccess = { OpState(message = done ?: current.op.message) },
                        onFailure = { e -> OpState(message = e.userMessage()) },
                    ),
                )
            }
        }
    }
}
