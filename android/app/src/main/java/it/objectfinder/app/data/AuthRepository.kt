package it.objectfinder.app.data

import it.objectfinder.app.data.remote.AccessTokenProvider
import it.objectfinder.app.data.remote.SignUpResult
import it.objectfinder.app.data.remote.SupabaseAuthApi
import it.objectfinder.app.data.remote.SupabaseException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
        val verifier = Pkce.newVerifier().also(settings::setPkceVerifier)
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
    suspend fun completeEmailLink(code: String) {
        val verifier = settings.pkceVerifier()
            ?: throw IllegalStateException("Email confermata. Accedi con email e password.")
        val token = api.exchangeCode(code, verifier)
        settings.setPkceVerifier(null)
        store.save(Session.from(token, clock()))
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
