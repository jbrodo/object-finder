package it.objectfinder.app.ui.auth

import android.app.Activity
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPasswordOption
import androidx.credentials.PasswordCredential
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException

/** Credential Manager: salvataggio e recupero della password dal gestore di sistema. */
object SavedCredentials {
    data class Saved(val email: String, val password: String)

    /** Propone le credenziali salvate per l'app; null se nessuna o se l'utente chiude il foglio. */
    suspend fun pick(activity: Activity): Saved? = try {
        val response = CredentialManager.create(activity).getCredential(
            activity,
            GetCredentialRequest(listOf(GetPasswordOption())),
        )
        (response.credential as? PasswordCredential)?.let { Saved(it.id, it.password) }
    } catch (_: NoCredentialException) {
        null // nessuna password salvata per l'app: caso normale al primo accesso
    } catch (_: GetCredentialException) {
        null // annullato o servizio non disponibile: si continua a mano
    }

    /** Chiede al gestore password di salvare/aggiornare le credenziali dopo un accesso riuscito. */
    suspend fun save(activity: Activity, email: String, password: String) {
        try {
            CredentialManager.create(activity).createCredential(activity, CreatePasswordRequest(email, password))
        } catch (_: CreateCredentialException) {
            // L'utente ha rifiutato o non c'è un gestore password: nessun problema.
        }
    }
}

enum class PasswordStrength(val label: String) { WEAK("Debole"), FAIR("Discreta"), STRONG("Forte") }

/** Stima semplice della robustezza (lunghezza + varietà di caratteri), solo come guida. */
fun passwordStrength(password: String): PasswordStrength {
    val classes = listOf(
        password.any { it.isLowerCase() },
        password.any { it.isUpperCase() },
        password.any { it.isDigit() },
        password.any { !it.isLetterOrDigit() },
    ).count { it }
    return when {
        password.length >= 12 && classes >= 3 -> PasswordStrength.STRONG
        password.length >= 8 && classes >= 2 -> PasswordStrength.FAIR
        else -> PasswordStrength.WEAK
    }
}

/** Requisito minimo per la registrazione (più severo del minimo di Supabase, 6). */
const val MIN_SIGNUP_PASSWORD = 8
