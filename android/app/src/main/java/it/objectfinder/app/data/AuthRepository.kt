package it.objectfinder.app.data

import it.objectfinder.app.data.remote.AccessTokenProvider
import it.objectfinder.app.data.remote.SignUpResult
import it.objectfinder.app.data.remote.SupabaseAuthApi
import it.objectfinder.app.data.remote.SupabaseException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Da dove arriva il link aperto: conferma della registrazione o ripristino della password. */
enum class EmailLinkFlow(val wire: String) {
    SIGN_UP("signup"),
    RECOVERY("recovery");

    companion object {
        fun fromWire(value: String?) = entries.firstOrNull { it.wire == value } ?: SIGN_UP
    }
}

sealed interface AuthOutcome {
    data object SignedIn : AuthOutcome
    data class ConfirmEmail(val email: String) : AuthOutcome
}

class AuthRepository(
    private val api: SupabaseAuthApi,
    private val store: SessionStore,
    private val settings: SettingsStore,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) : AccessTokenProvider {
    private val refreshMutex = Mutex()

    val session: StateFlow<Session?> = store.session

    suspend fun signIn(email: String, password: String) {
        val token = api.signInWithPassword(email.trim(), password)
        store.save(Session.from(token, clock(), email.trim()))
    }

    suspend fun signUp(email: String, password: String): AuthOutcome {
        // Il verifier resta sul telefono finché non si apre il link dell'email (anche giorni dopo).
        val verifier = Pkce.newVerifier().also { settings.setPkceVerifier(it, EmailLinkFlow.SIGN_UP.wire) }
        return when (val result = api.signUp(email.trim(), password, Pkce.challenge(verifier), Pkce.REDIRECT_URL)) {
            is SignUpResult.SignedIn -> {
                store.save(Session.from(result.token, clock(), email.trim()))
                AuthOutcome.SignedIn
            }
            is SignUpResult.ConfirmationRequired -> AuthOutcome.ConfirmEmail(result.email)
        }
    }

    /**
     * Link di conferma aperto sul telefono: email confermata e accesso senza password.
     * Se il verifier non c'è (registrazione da un altro telefono, dati cancellati) l'email è comunque
     * confermata: si accede con email e password.
     */
    suspend fun completeEmailLink(code: String): EmailLinkFlow {
        val flow = EmailLinkFlow.fromWire(settings.pkceFlow())
        val verifier = settings.pkceVerifier() ?: throw IllegalStateException(
            if (flow == EmailLinkFlow.RECOVERY) "Apri il link sullo stesso telefono da cui hai chiesto il ripristino, oppure richiedilo di nuovo."
            else "Email confermata. Accedi con email e password."
        )
        val token = api.exchangeCode(code, verifier)
        settings.setPkceVerifier(null, null)
        store.save(Session.from(token, clock()))
        return flow
    }

    /** "Password dimenticata?": email con link che riporta nell'app per sceglierne una nuova. */
    suspend fun requestPasswordReset(email: String) {
        val verifier = Pkce.newVerifier().also { settings.setPkceVerifier(it, EmailLinkFlow.RECOVERY.wire) }
        api.recover(email.trim(), Pkce.challenge(verifier), Pkce.REDIRECT_URL)
    }

    /** Nuova password per l'account collegato (dopo il link di ripristino o da Impostazioni). */
    suspend fun changePassword(newPassword: String) {
        api.updatePassword(validAccessToken(), newPassword)
    }

    /** Logout: il token viene revocato lato server best-effort; i dati locali restano sul dispositivo. */
    suspend fun signOut() {
        store.current?.let { runCatching { api.signOut(it.accessToken) } }
        store.clear()
        settings.clearAccountData()
    }

    override suspend fun validAccessToken(): String = refreshMutex.withLock {
        val current = store.current ?: throw SupabaseException.NotAuthenticated()
        if (!current.isExpiring(clock())) current.accessToken else refreshLocked(current)
    }

    override suspend fun forceRefresh(): String = refreshMutex.withLock {
        val current = store.current ?: throw SupabaseException.NotAuthenticated()
        refreshLocked(current)
    }

    private suspend fun refreshLocked(current: Session): String {
        val token = try {
            api.refresh(current.refreshToken)
        } catch (e: SupabaseException.Http) {
            // Refresh token revocato/scaduto: la sessione non è più recuperabile.
            if (e.status in 400..499) {
                store.clear()
                throw SupabaseException.NotAuthenticated()
            }
            throw e
        }
        val renewed = Session.from(token, clock(), current.email)
        store.save(renewed)
        return renewed.accessToken
    }
}
