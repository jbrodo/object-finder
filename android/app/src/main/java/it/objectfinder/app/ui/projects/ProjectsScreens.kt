package it.objectfinder.app.ui.projects

import it.objectfinder.app.ui.common.readableWidth
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import it.objectfinder.app.data.InviteEmail
import androidx.compose.material.icons.filled.Email
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.content.Context
import android.content.Intent
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.objectfinder.app.data.ActiveProject
import it.objectfinder.app.data.PendingInvite
import it.objectfinder.app.data.ProjectMember
import it.objectfinder.app.data.ProjectRole

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(viewModel: ProjectsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var creating by remember { mutableStateOf(false) }

    LaunchedEffect(state.op.message) {
        state.op.message?.let { snackbar.showSnackbar(it); viewModel.consumeMessage() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Progetti") },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    IconButton(onClick = viewModel::refresh) { Icon(Icons.Default.Refresh, contentDescription = "Aggiorna") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Nuovo progetto") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        // Inset in basso come contentPadding: l'elenco scorre sotto la barra di navigazione.
        Column(Modifier.fillMaxSize().padding(padding.withoutBottom()).consumeWindowInsets(padding).readableWidth()) {
            if (state.op.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                "Ogni progetto ha il suo archivio di oggetti e i suoi membri. " +
                    "Scegli quello su cui lavorare.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
            LazyColumn(contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 96.dp)) {
                items(state.projects, key = { it.id }, contentType = { "project" }) { project ->
                    ProjectRow(project, active = project.id == state.activeId, onClick = { viewModel.select(project) })
                    HorizontalDivider()
                }
            }
        }
    }

    if (creating) {
        NameDialog(
            title = "Nuovo progetto",
            confirm = "Crea",
            onDismiss = { creating = false },
            onConfirm = { name -> creating = false; viewModel.create(name) },
        )
    }
}

@Composable
private fun ProjectRow(project: ActiveProject, active: Boolean, onClick: () -> Unit) {
    ListItem(
        supportingContent = { Text(project.role?.label ?: "") },
        trailingContent = {
            if (active) Icon(Icons.Default.CheckCircle, contentDescription = "Attivo", tint = MaterialTheme.colorScheme.primary)
        },
        modifier = Modifier.clickable(onClick = onClick),
    ) { Text(project.name) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MembersScreen(
    viewModel: MembersViewModel,
    project: ActiveProject?,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var adding by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf<ProjectMember?>(null) }
    var confirmRevoke by remember { mutableStateOf<PendingInvite?>(null) }
    val context = LocalContext.current
    val canManage = project?.canManageMembers == true

    LaunchedEffect(state.op.message) {
        state.op.message?.let { snackbar.showSnackbar(it); viewModel.consumeMessage() }
    }
    LaunchedEffect(state.left) { if (state.left) onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Membri · ${project?.name ?: ""}") },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    if (project?.role != null && project.role != ProjectRole.OWNER) {
                        IconButton(onClick = { confirmLeave = true }) {
                            Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "Esci dal progetto")
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (canManage) {
                ExtendedFloatingActionButton(
                    onClick = { adding = true },
                    icon = { Icon(Icons.Default.PersonAdd, contentDescription = null) },
                    text = { Text("Aggiungi membro") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        // Inset in basso come contentPadding: l'elenco scorre sotto la barra di navigazione.
        Column(Modifier.fillMaxSize().padding(padding.withoutBottom()).consumeWindowInsets(padding).readableWidth()) {
            if (state.op.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!canManage) {
                Text(
                    "Solo proprietario e amministratori possono gestire i membri.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            LazyColumn(contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 96.dp)) {
                if (state.invites.isNotEmpty()) {
                    item(key = "invites-header", contentType = "header") {
                        Text(
                            "Inviti in attesa",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp),
                        )
                    }
                    items(state.invites, key = { "invite-${it.id}" }, contentType = { "invite" }) { invite ->
                        ListItem(
                            supportingContent = {
                                val expires = invite.expiresAt
                                Text(
                                    when {
                                        invite.isExpired() -> "${invite.role.label} · scaduto: reinvialo per rinnovarlo"
                                        expires != null -> "${invite.role.label} · in attesa · scade il ${inviteDate(expires)}"
                                        else -> "${invite.role.label} · in attesa di registrazione"
                                    },
                                    color = if (invite.isExpired()) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            trailingContent = {
                                Row {
                                    IconButton(onClick = { viewModel.emailInvite(invite) }, enabled = !state.op.busy) {
                                        Icon(Icons.Default.Email, contentDescription = "Invia di nuovo l'email")
                                    }
                                    IconButton(onClick = { viewModel.shareInvite(invite) }) {
                                        Icon(Icons.Default.Share, contentDescription = "Condividi invito")
                                    }
                                    IconButton(onClick = { confirmRevoke = invite }) {
                                        Icon(Icons.Default.Close, contentDescription = "Revoca invito")
                                    }
                                }
                            },
                        ) { Text(invite.email) }
                        HorizontalDivider()
                    }
                    item(key = "members-header", contentType = "header") {
                        Text(
                            "Membri",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp),
                        )
                    }
                }
                items(state.members, key = { it.userId }, contentType = { "member" }) { member ->
                    MemberRow(
                        member = member,
                        canManage = canManage && member.role != ProjectRole.OWNER && !member.isMe,
                        onRoleChange = { viewModel.changeRole(member, it) },
                        onRemove = { confirmRemove = member },
                    )
                    HorizontalDivider()
                }
                // Da soli il progetto non si condivide: si spiega come farlo, anche con chi non ha un account.
                if (canManage && state.members.size == 1 && state.invites.isEmpty() && !state.op.busy) {
                    item(key = "alone-hint", contentType = "hint") {
                        Text(
                            "Sei l'unico membro. Aggiungi chi deve vedere e ritrovare questi oggetti: " +
                                "se non ha ancora un account, l'invito vale quando si registra con quella email.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }

    if (adding) AddMemberDialog(onDismiss = { adding = false }, onConfirm = { email, role ->
        adding = false
        viewModel.add(email, role)
    })
    confirmRemove?.let { member ->
        ConfirmDialog(
            title = "Rimuovere ${member.email}?",
            text = "Non potrà più vedere né modificare gli oggetti del progetto.",
            confirm = "Rimuovi",
            onDismiss = { confirmRemove = null },
            onConfirm = { confirmRemove = null; viewModel.remove(member) },
        )
    }
    confirmRevoke?.let { invite ->
        ConfirmDialog(
            title = "Revocare l'invito?",
            text = "${invite.email} non potrà più entrare nel progetto con questo invito.",
            confirm = "Revoca",
            onDismiss = { confirmRevoke = null },
            onConfirm = { confirmRevoke = null; viewModel.revokeInvite(invite) },
        )
    }
    state.justInvited?.let { invite ->
        AlertDialog(
            onDismissRequest = viewModel::consumeInvite,
            title = { Text(if (state.inviteEmail == InviteEmail.Sent) "Invito inviato" else "Invito creato") },
            text = {
                val after = "Dopo la registrazione con questa email (e la conferma dell'indirizzo) entrerà nel " +
                    "progetto come ${invite.role.label.lowercase()}."
                Text(
                    when (val mail = state.inviteEmail) {
                        InviteEmail.Sent -> "Abbiamo mandato a ${invite.email} un'email con il link all'app. $after"
                        is InviteEmail.NotSent ->
                            "${invite.email} non ha ancora un account. L'email non è partita (${mail.reason}): " +
                                "mandagli tu l'invito. $after"
                        else -> "${invite.email} non ha ancora un account. Mandagli l'invito. $after"
                    }
                )
            },
            confirmButton = {
                if (state.inviteEmail == InviteEmail.Sent) {
                    TextButton(onClick = viewModel::consumeInvite) { Text("OK") }
                } else {
                    TextButton(onClick = {
                        viewModel.consumeInvite()
                        shareInviteText(context, inviteMessage(project?.name ?: "Object Finder", invite))
                    }) { Text("Condividi invito") }
                }
            },
            dismissButton = {
                if (state.inviteEmail == InviteEmail.Sent) {
                    TextButton(onClick = {
                        viewModel.consumeInvite()
                        shareInviteText(context, inviteMessage(project?.name ?: "Object Finder", invite))
                    }) { Text("Condividi anche") }
                } else {
                    TextButton(onClick = viewModel::consumeInvite) { Text("Più tardi") }
                }
            },
        )
    }
    if (confirmLeave) {
        ConfirmDialog(
            title = "Uscire dal progetto?",
            text = "Gli oggetti del progetto non saranno più accessibili. Potrai rientrare solo se invitato di nuovo.",
            confirm = "Esci",
            onDismiss = { confirmLeave = false },
            onConfirm = { confirmLeave = false; viewModel.leave() },
        )
    }
}

@Composable
private fun MemberRow(
    member: ProjectMember,
    canManage: Boolean,
    onRoleChange: (ProjectRole) -> Unit,
    onRemove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    ListItem(
        supportingContent = { Text(member.role.label) },
        trailingContent = {
            if (canManage) Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Azioni") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    ProjectRole.assignable.filter { it != member.role }.forEach { role ->
                        DropdownMenuItem(
                            text = { Text("Rendi ${role.label.lowercase()}") },
                            onClick = { menu = false; onRoleChange(role) },
                        )
                    }
                    DropdownMenuItem(text = { Text("Rimuovi dal progetto") }, onClick = { menu = false; onRemove() })
                }
            }
        },
    ) { Text(member.email + if (member.isMe) " (tu)" else "") }
}

@Composable
private fun AddMemberDialog(onDismiss: () -> Unit, onConfirm: (String, ProjectRole) -> Unit) {
    var email by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(ProjectRole.EDITOR) }
    val valid = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(email.trim())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Aggiungi membro") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Se la persona ha già un account viene aggiunta subito, altrimenti riceverà un invito " +
                        "valido quando si registrerà con questa email.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ProjectRole.assignable.forEach { option ->
                        FilterChip(selected = role == option, onClick = { role = option }, label = { Text(option.label) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(email.trim(), role) }, enabled = valid) { Text("Aggiungi") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}

@Composable
private fun NameDialog(title: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nome") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}

@Composable
private fun ConfirmDialog(title: String, text: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}

/** Creato una volta: le righe degli inviti lo riusano (immutabile e thread-safe). */
private val inviteDateFormat = java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale.ITALIAN)

internal fun inviteDate(millis: Long): String =
    inviteDateFormat.format(java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()))

internal fun inviteMessage(projectName: String, invite: PendingInvite): String =
    "Ti ho invitato nel progetto «$projectName» su Object Finder come ${invite.role.label.lowercase()}.\n" +
        "Installa l'app e registrati con questa email: ${invite.email}\n" +
        "Dopo aver confermato l'indirizzo email, il progetto comparirà al primo accesso. L'invito vale 30 giorni."

private fun shareInviteText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Invito a Object Finder")
        putExtra(Intent.EXTRA_TEXT, text)
    }
    runCatching { context.startActivity(Intent.createChooser(send, "Invia invito")) }
}

@Composable
private fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro") }
}

/** Gli inset di [Scaffold] tranne quello in basso (da passare come contentPadding agli elenchi). */
@Composable
private fun PaddingValues.withoutBottom(): PaddingValues {
    val direction = androidx.compose.ui.platform.LocalLayoutDirection.current
    return PaddingValues(
        start = calculateStartPadding(direction),
        top = calculateTopPadding(),
        end = calculateEndPadding(direction),
    )
}
