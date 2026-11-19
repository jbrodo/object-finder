package it.objectfinder.app.data

import android.content.Context
import it.objectfinder.app.data.remote.AuthTokenDto
import it.objectfinder.app.data.remote.supabaseJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

@Serializable
data class Session(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochSec: Long,
    val userId: String,
    val email: String,
) {
    fun isExpiring(nowEpochSec: Long, marginSec: Long = 60): Boolean = nowEpochSec >= expiresAtEpochSec - marginSec

    companion object {
        fun from(token: AuthTokenDto, nowEpochSec: Long, fallbackEmail: String = ""): Session = Session(
            accessToken = token.accessToken,
            refreshToken = token.refreshToken,
            expiresAtEpochSec = token.expiresAt ?: (nowEpochSec + token.expiresIn),
            userId = token.user.id,
            email = token.user.email ?: fallbackEmail,
        )
    }
}

/**
 * Persistenza della sessione Supabase in SharedPreferences private dell'app
 * (backup disabilitato nel manifest). Per un hardening ulteriore cifrare con Android Keystore.
 */
class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("auth_session", Context.MODE_PRIVATE)
    private val _session = MutableStateFlow(read())
    val session: StateFlow<Session?> = _session.asStateFlow()

    val current: Session? get() = _session.value

    fun save(session: Session) {
        prefs.edit().putString(KEY, supabaseJson.encodeToString(Session.serializer(), session)).apply()
        _session.value = session
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
        _session.value = null
    }

    private fun read(): Session? = prefs.getString(KEY, null)?.let {
        runCatching { supabaseJson.decodeFromString(Session.serializer(), it) }.getOrNull()
    }

    private companion object {
        const val KEY = "session"
    }
}
