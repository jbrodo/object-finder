package it.objectfinder.app.data.remote

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Properties

/** Config da `assets/config.properties` (non versionato). Solo anon/publishable key: mai service_role. */
data class AppConfig(val supabaseUrl: String, val supabaseAnonKey: String) {
    val isSupabaseConfigured: Boolean
        get() = supabaseUrl.startsWith("https://") &&
            supabaseAnonKey.isNotBlank() &&
            !supabaseAnonKey.startsWith("YOUR_")

    companion object {
        fun load(context: Context): AppConfig {
            val props = Properties()
            runCatching { context.assets.open("config.properties").use { props.load(it) } }
            return AppConfig(
                supabaseUrl = normalizeSupabaseUrl(props.getProperty("SUPABASE_URL", "")),
                supabaseAnonKey = props.getProperty("SUPABASE_ANON_KEY", "").trim(),
            )
        }
    }
}

/**
 * URL base del progetto (`https://<ref>.supabase.co`). Tollera gli URL copiati con il percorso di
 * un servizio (es. `.../rest/v1/` dalla pagina API): l'app aggiunge da sé /rest/v1, /auth/v1, /storage/v1.
 */
internal fun normalizeSupabaseUrl(raw: String): String {
    var url = raw.trim().trimEnd('/')
    val servicePath = Regex("/(rest|auth|storage)/v1$", RegexOption.IGNORE_CASE)
    while (servicePath.containsMatchIn(url)) url = url.replace(servicePath, "").trimEnd('/')
    return url
}

/** Errori tipizzati: la UI e il worker decidono se ritentare, chiedere login o mostrare un messaggio. */
sealed class SupabaseException(message: String) : Exception(message) {
    class NotConfigured : SupabaseException("Supabase non configurato: compila assets/config.properties")
    class NotAuthenticated : SupabaseException("Sessione assente o scaduta: effettua l'accesso")
    class Http(val status: Int, message: String) : SupabaseException(message)
}

val supabaseJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = true
}

class SupabaseHttp(val config: AppConfig) {
    val client = HttpClient(OkHttp) {
        expectSuccess = false
        install(ContentNegotiation) { json(supabaseJson) }
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 10_000
        }
    }

    val baseUrl: String get() = config.supabaseUrl
    val anonKey: String get() = config.supabaseAnonKey

    fun requireConfigured() {
        if (!config.isSupabaseConfigured) throw SupabaseException.NotConfigured()
    }
}

/** Lancia [SupabaseException.Http] con il messaggio leggibile restituito da GoTrue/PostgREST. */
suspend fun HttpResponse.ensureSuccess(): HttpResponse {
    if (status.isSuccess()) return this
    val body = runCatching { bodyAsText() }.getOrDefault("")
    val raw = parseSupabaseError(body)
    throw SupabaseException.Http(status.value, raw?.let(::localizeServerMessage) ?: "Errore HTTP ${status.value}")
}

internal fun parseSupabaseError(body: String): String? {
    val obj = runCatching { supabaseJson.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
    // GoTrue: error_description | msg ; PostgREST: message
    return listOf("error_description", "msg", "message", "error")
        .firstNotNullOfOrNull { key -> obj[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() } }
}

private val KNOWN_MESSAGES = listOf(
    "should be different from the old password" to "La nuova password deve essere diversa da quella attuale",
    "requires reauthentication" to "Per sicurezza esci e rientra, poi cambia la password",
    "for security purposes, you can only request this after" to "Hai già chiesto un link da poco: attendi un minuto e riprova",
    // PostgREST (PGRST204): l'app è più nuova dello schema; le modifiche restano in coda, nulla va perso.
    "in the schema cache" to "Il server non è ancora aggiornato per questa versione dell'app: le modifiche restano qui e partiranno appena lo sarà",
    "invalid login credentials" to "Email o password non corretti",
    "email not confirmed" to "Email non ancora confermata: controlla la tua casella",
    "user already registered" to "Esiste già un account con questa email",
    "password should be at least" to "La password è troppo corta",
    "no account found for this email" to "Nessun account registrato con questa email",
    "the owner role cannot be assigned" to "Il ruolo di proprietario non è assegnabile",
    "the owner role cannot be changed" to "Il ruolo del proprietario non può essere cambiato",
    "the project owner cannot be removed" to "Il proprietario non può essere rimosso dal progetto",
    "only owners and admins" to "Operazione riservata a proprietari e amministratori",
    "not a project member" to "Non fai parte di questo progetto",
    "row-level security" to "Permessi insufficienti per questa operazione",
)

/** Messaggi server noti → italiano; gli altri restano invariati. */
internal fun localizeServerMessage(message: String): String {
    val lower = message.lowercase()
    return KNOWN_MESSAGES.firstOrNull { (key, _) -> lower.contains(key) }?.second ?: message
}
