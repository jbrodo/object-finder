package it.objectfinder.app.ui.auth

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Nuova password (dopo il link di ripristino o da Impostazioni): campo "nuova password" così il
 * gestore password ne propone una forte e la salva; robustezza e occhio come in registrazione.
 */
@Composable
fun NewPasswordDialog(
    title: String,
    message: String?,
    email: String,
    onSave: suspend (String) -> Unit,
    onDone: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val activity = LocalActivity.current
    val state = rememberTextFieldState()
    val password by remember { derivedStateOf { state.text.toString() } }
    var visible by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val valid = password.length >= MIN_SIGNUP_PASSWORD

    fun save() {
        if (!valid || busy) return
        busy = true; error = null
        scope.launch {
            runCatching { onSave(password) }
                .onSuccess {
                    // Aggiorna la password salvata nel gestore password del telefono.
                    if (email.isNotBlank()) activity?.let { SavedCredentials.save(it, email, password) }
                    onDone("Password aggiornata")
                }
                .onFailure { error = it.message ?: "Password non aggiornata" }
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                OutlinedSecureTextField(
                    state = state,
                    label = { Text("Nuova password") },
                    supportingText = {
                        val strength = passwordStrength(password)
                        Text(
                            if (password.isEmpty()) "Almeno $MIN_SIGNUP_PASSWORD caratteri: puoi usare quella suggerita dal gestore password"
                            else "Robustezza: ${strength.label}" +
                                if (!valid) " · almeno $MIN_SIGNUP_PASSWORD caratteri" else "",
                            color = when {
                                password.isEmpty() -> MaterialTheme.colorScheme.onSurfaceVariant
                                strength == PasswordStrength.STRONG -> MaterialTheme.colorScheme.primary
                                strength == PasswordStrength.WEAK -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    },
                    isError = password.isNotEmpty() && !valid,
                    textObfuscationMode = if (visible) TextObfuscationMode.Visible else TextObfuscationMode.RevealLastTyped,
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(
                                if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (visible) "Nascondi password" else "Mostra password",
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    onKeyboardAction = { save() },
                    modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.NewPassword },
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = ::save, enabled = valid && !busy) {
                if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Salva password")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Annulla") } },
    )
}

/** "Password dimenticata?": chiede l'email e manda il link di ripristino (ritorno nell'app). */
@Composable
fun ForgotPasswordDialog(
    initialEmail: String,
    onSend: suspend (String) -> Unit,
    onSent: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf(initialEmail.trim()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val valid = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(email.trim())

    fun send() {
        if (!valid || busy) return
        busy = true; error = null
        scope.launch {
            runCatching { onSend(email.trim()) }
                // Stesso messaggio che l'account esista o no: non si rivela chi è registrato.
                .onSuccess {
                    onSent(
                        "Se esiste un account con ${email.trim()}, riceverai un'email. Apri il link da questo " +
                            "telefono: tornerai nell'app per scegliere la nuova password."
                    )
                }
                .onFailure { error = it.message ?: "Invio non riuscito" }
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Password dimenticata") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Ti mandiamo un link per sceglierne una nuova.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    singleLine = true,
                    isError = email.isNotBlank() && !valid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.EmailAddress + ContentType.Username },
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = ::send, enabled = valid && !busy) {
                if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Invia link")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Annulla") } },
    )
}
