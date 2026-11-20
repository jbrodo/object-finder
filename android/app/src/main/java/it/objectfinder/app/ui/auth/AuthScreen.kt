package it.objectfinder.app.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import it.objectfinder.app.data.AppContainer
import it.objectfinder.app.data.AuthOutcome
import kotlinx.coroutines.launch

private enum class AuthMode(val label: String) { SIGN_IN("Accedi"), SIGN_UP("Registrati") }

/**
 * Onboarding/login Supabase. L'account è facoltativo: senza, l'app resta un archivio locale.
 * Dopo l'accesso viene garantito un progetto cloud (bootstrap) e avviata la sincronizzazione.
 */
@Composable
fun AuthScreen(
    container: AppContainer,
    onDone: () -> Unit,
    onContinueOffline: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(AuthMode.SIGN_IN) }
    var email by remember { mutableStateOf("") }
    // Campo password sicuro (non copiabile, non registrato dalla tastiera); `password` legge sempre il valore attuale.
    val passwordState = rememberTextFieldState()
    val password by remember { derivedStateOf { passwordState.text.toString() } }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var passwordVisible by remember { mutableStateOf(false) }
    val activity = LocalActivity.current
    val configured = container.config.isSupabaseConfigured

    val emailValid = EMAIL_REGEX.matches(email.trim())
    // Accesso: minimo Supabase (6) per gli account esistenti; registrazione: requisito più severo.
    val minLength = if (mode == AuthMode.SIGN_UP) MIN_SIGNUP_PASSWORD else 6
    val passwordValid = password.length >= minLength
    val canSubmit = configured && emailValid && passwordValid && !busy

    /** Offre al gestore password di salvare le credenziali appena usate con successo. */
    suspend fun offerSave() {
        activity?.let { SavedCredentials.save(it, email.trim(), password) }
    }

    fun submit() {
        if (!canSubmit) return
        busy = true; error = null; info = null
        scope.launch {
            runCatching {
                when (mode) {
                    AuthMode.SIGN_IN -> {
                        container.authRepository.signIn(email, password)
                        AuthOutcome.SignedIn
                    }
                    AuthMode.SIGN_UP -> container.authRepository.signUp(email, password)
                }
            }.onSuccess { outcome ->
                when (outcome) {
                    AuthOutcome.SignedIn -> {
                        offerSave()
                        // Bootstrap progetto best-effort: se fallisce, ci riprova la sync.
                        runCatching { container.projectRepository.ensureActiveProject() }
                        onDone()
                    }
                    is AuthOutcome.ConfirmEmail -> {
                        offerSave()
                        info = "Ti abbiamo inviato un'email di conferma a ${outcome.email}. Apri il link da questo " +
                            "telefono: tornerai qui già dentro. Se lo apri altrove, poi accedi con email e password."
                        mode = AuthMode.SIGN_IN
                    }
                }
            }.onFailure { error = it.message ?: "Accesso non riuscito" }
            busy = false
        }
    }

    /** Credenziali salvate nel gestore password: compila e accede. */
    fun useSavedCredentials(auto: Boolean) {
        val act = activity ?: return
        scope.launch {
            val saved = SavedCredentials.pick(act) ?: run {
                if (!auto) info = "Nessuna password salvata per Object Finder su questo dispositivo."
                return@launch
            }
            mode = AuthMode.SIGN_IN
            email = saved.email
            passwordState.setTextAndPlaceCursorAtEnd(saved.password)
            submit()
        }
    }

    // All'apertura propone subito le credenziali salvate (se non ce ne sono non appare nulla).
    LaunchedEffect(configured) { if (configured) useSavedCredentials(auto = true) }

    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Text("Object Finder", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Registra i tuoi oggetti con posizione GPS e ritrovali su mappa. " +
                    "Con un account li sincronizzi nel cloud e li condividi con il tuo progetto.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!configured) {
                Text(
                    "Supabase non è configurato in questo build (assets/config.properties). " +
                        "Puoi comunque usare l'app offline.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                AuthMode.entries.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = mode == option,
                        onClick = { mode = option; error = null },
                        shape = SegmentedButtonDefaults.itemShape(index, AuthMode.entries.size),
                        label = { Text(option.label) },
                    )
                }
            }

            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                singleLine = true,
                isError = email.isNotBlank() && !emailValid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentType = ContentType.EmailAddress + ContentType.Username },
            )
            OutlinedSecureTextField(
                state = passwordState,
                label = { Text("Password") },
                supportingText = {
                    if (mode == AuthMode.SIGN_UP) {
                        val strength = passwordStrength(password)
                        Text(
                            if (password.isEmpty()) "Almeno $MIN_SIGNUP_PASSWORD caratteri: puoi usare la password suggerita dal gestore password"
                            else "Robustezza: ${strength.label}" +
                                if (password.length < MIN_SIGNUP_PASSWORD) " · almeno $MIN_SIGNUP_PASSWORD caratteri" else "",
                            color = when {
                                password.isEmpty() -> MaterialTheme.colorScheme.onSurfaceVariant
                                strength == PasswordStrength.STRONG -> MaterialTheme.colorScheme.primary
                                strength == PasswordStrength.WEAK -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                },
                isError = password.isNotEmpty() && !passwordValid,
                textObfuscationMode = if (passwordVisible) TextObfuscationMode.Visible else TextObfuscationMode.RevealLastTyped,
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (passwordVisible) "Nascondi password" else "Mostra password",
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                onKeyboardAction = { submit() },
                modifier = Modifier
                    .fillMaxWidth()
                    // "Nuova password" in registrazione: il gestore password propone una password forte.
                    .semantics {
                        contentType = if (mode == AuthMode.SIGN_UP) ContentType.NewPassword else ContentType.Password
                    },
            )

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            info?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

            Button(onClick = ::submit, enabled = canSubmit, modifier = Modifier.fillMaxWidth()) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(mode.label)
            }
            if (configured && mode == AuthMode.SIGN_IN) {
                TextButton(onClick = { useSavedCredentials(auto = false) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Key, contentDescription = null, Modifier.size(18.dp))
                    Text("  Usa password salvata")
                }
            }
            TextButton(onClick = onContinueOffline, modifier = Modifier.fillMaxWidth()) {
                Text("Continua senza account")
            }
        }
    }
}

private val EMAIL_REGEX = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
