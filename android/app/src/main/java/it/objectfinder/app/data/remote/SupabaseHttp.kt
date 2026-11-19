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
                supabaseUrl = props.getProperty("SUPABASE_URL", "").trim().trimEnd('/'),
                supabaseAnonKey = props.getProperty("SUPABASE_ANON_KEY", "").trim(),
            )
        }
    }
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
    throw SupabaseException.Http(status.value, parseSupabaseError(body) ?: "Errore HTTP ${status.value}")
}

internal fun parseSupabaseError(body: String): String? {
    val obj = runCatching { supabaseJson.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
    // GoTrue: error_description | msg ; PostgREST: message
    return listOf("error_description", "msg", "message", "error")
        .firstNotNullOfOrNull { key -> obj[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() } }
}
