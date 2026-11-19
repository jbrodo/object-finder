package it.objectfinder.app.data

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.util.Properties

class SupabaseApi(context: Context) {
    private val config = Properties().apply {
        runCatching {
            context.assets.open("config.properties").use { load(it) }
        }
    }
    private val baseUrl = config.getProperty("SUPABASE_URL", "").trimEnd('/')
    private val anonKey = config.getProperty("SUPABASE_ANON_KEY", "")

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
        }
    }

    val isConfigured: Boolean get() = baseUrl.startsWith("https://") && anonKey.isNotBlank()

    suspend fun upsertObject(item: ObjectDto) {
        check(isConfigured) { "Configura Supabase in assets/config.properties" }
        client.post("$baseUrl/rest/v1/objects?on_conflict=id") {
            header("apikey", anonKey)
            // Per l'MVP anon key: usare un utente/sessione autenticata nella milestone Auth.
            // La migration abilita RLS: senza sessione valida il server rifiuta correttamente la scrittura.
            header("Authorization", "Bearer $anonKey")
            header("Prefer", "resolution=merge-duplicates,return=minimal")
            contentType(ContentType.Application.Json)
            setBody(item)
        }
    }
}
