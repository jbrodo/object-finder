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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    val configured = container.config.isSupabaseConfigured

    val emailValid = EMAIL_REGEX.matches(email.trim())
    val passwordValid = password.length >= 6
    val canSubmit = configured && emailValid && passwordValid && !busy

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
                        // Bootstrap progetto best-effort: se fallisce, ci riprova la sync.
                        runCatching { container.projectRepository.ensureActiveProject() }
                        onDone()
                    }
                    is AuthOutcome.ConfirmEmail -> {
                        info = "Ti abbiamo inviato un'email di conferma a ${outcome.email}. Confermala e poi accedi."
                        mode = AuthMode.SIGN_IN
                    }
                }
            }.onFailure { error = it.message ?: "Accesso non riuscito" }
            busy = false
        }
    }

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
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                supportingText = { if (mode == AuthMode.SIGN_UP) Text("Almeno 6 caratteri") },
                singleLine = true,
                isError = password.isNotEmpty() && !passwordValid,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            info?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

            Button(onClick = ::submit, enabled = canSubmit, modifier = Modifier.fillMaxWidth()) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(mode.label)
            }
            TextButton(onClick = onContinueOffline, modifier = Modifier.fillMaxWidth()) {
                Text("Continua senza account")
            }
        }
    }
}

private val EMAIL_REGEX = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
