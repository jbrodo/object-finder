package it.objectfinder.app.data

import android.content.Context
import android.util.Base64
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
 * Sessione Supabase cifrata (AES-256-GCM, chiave nell'Android Keystore) nelle SharedPreferences
 * private dell'app; backup e trasferimento dispositivo sono comunque disabilitati nel manifest.
 * Le sessioni salvate in chiaro dalle versioni precedenti vengono migrate al primo avvio.
 */
class SessionStore(context: Context, private val key: KeystoreKey = KeystoreKey("object_finder_session")) {
    private val prefs = context.getSharedPreferences("auth_session", Context.MODE_PRIVATE)
    private val _session = MutableStateFlow(read())
    val session: StateFlow<Session?> = _session.asStateFlow()

    val current: Session? get() = _session.value

    fun save(session: Session) {
        // Keystore non disponibile (raro): la sessione vale finché l'app resta aperta, mai salvata in chiaro.
        runCatching { persist(session) }
        _session.value = session
    }

    /** Solo scrittura cifrata: usabile anche durante l'inizializzazione (migrazione), senza toccare lo stato. */
    private fun persist(session: Session) {
        val json = supabaseJson.encodeToString(Session.serializer(), session)
        val sealed = Base64.encodeToString(AesGcm.seal(key.get(), json.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        prefs.edit().putString(KEY_SEALED, sealed).remove(KEY_LEGACY_PLAIN).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_SEALED).remove(KEY_LEGACY_PLAIN).apply()
        _session.value = null
    }

    private fun read(): Session? {
        prefs.getString(KEY_SEALED, null)?.let { sealed ->
            return runCatching {
                val json = String(AesGcm.open(key.get(), Base64.decode(sealed, Base64.NO_WRAP)), Charsets.UTF_8)
                supabaseJson.decodeFromString(Session.serializer(), json)
            }.getOrElse {
                // Chiave persa o dato alterato: la sessione non è recuperabile, si rifà l'accesso.
                prefs.edit().remove(KEY_SEALED).apply()
                null
            }
        }
        // Migrazione dalle versioni ≤ 1.0 (sessione in chiaro).
        val legacy = prefs.getString(KEY_LEGACY_PLAIN, null) ?: return null
        val session = runCatching { supabaseJson.decodeFromString(Session.serializer(), legacy) }.getOrNull()
        if (session == null) {
            prefs.edit().remove(KEY_LEGACY_PLAIN).apply()
            return null
        }
        // Se il Keystore non è disponibile la sessione resta valida in memoria; si riprova al prossimo salvataggio.
        runCatching { persist(session) }
        return session
    }

    private companion object {
        const val KEY_SEALED = "session_sealed"
        const val KEY_LEGACY_PLAIN = "session"
    }
}
