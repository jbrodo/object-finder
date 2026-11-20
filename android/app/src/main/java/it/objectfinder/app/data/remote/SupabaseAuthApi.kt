package it.objectfinder.app.data.remote

import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

@Serializable
data class AuthUserDto(val id: String, val email: String? = null)

@Serializable
data class AuthTokenDto(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Long = 3600,
    @SerialName("expires_at") val expiresAt: Long? = null,
    val user: AuthUserDto,
)

@Serializable
private data class Credentials(val email: String, val password: String)

@Serializable
private data class SignUpBody(
    val email: String,
    val password: String,
    @SerialName("code_challenge") val codeChallenge: String,
    @SerialName("code_challenge_method") val codeChallengeMethod: String = "s256",
)

@Serializable
private data class PkceBody(
    @SerialName("auth_code") val authCode: String,
    @SerialName("code_verifier") val codeVerifier: String,
)

@Serializable
private data class RefreshBody(@SerialName("refresh_token") val refreshToken: String)

sealed interface SignUpResult {
    data class SignedIn(val token: AuthTokenDto) : SignUpResult
    /** Il progetto Supabase richiede conferma email prima del primo login. */
    data class ConfirmationRequired(val email: String) : SignUpResult
}

/** Client minimale GoTrue (Supabase Auth) REST. */
class SupabaseAuthApi(private val http: SupabaseHttp) {

    suspend fun signInWithPassword(email: String, password: String): AuthTokenDto {
        http.requireConfigured()
        return http.client.post("${http.baseUrl}/auth/v1/token") {
            parameter("grant_type", "password")
            header("apikey", http.anonKey)
            contentType(ContentType.Application.Json)
            setBody(Credentials(email, password))
        }.ensureSuccess().body()
    }

    /**
     * Registrazione con ritorno nell'app: il link dell'email di conferma porta a [redirectTo]
     * (deve essere tra i Redirect URLs di Supabase, altrimenti si usa il Site URL del progetto).
     */
    suspend fun signUp(email: String, password: String, codeChallenge: String, redirectTo: String): SignUpResult {
        http.requireConfigured()
        val json: JsonObject = http.client.post("${http.baseUrl}/auth/v1/signup") {
            parameter("redirect_to", redirectTo)
            header("apikey", http.anonKey)
            contentType(ContentType.Application.Json)
            setBody(SignUpBody(email, password, codeChallenge))
        }.ensureSuccess().body()
        return if (json.containsKey("access_token")) {
            SignUpResult.SignedIn(supabaseJson.decodeFromJsonElement(json))
        } else {
            SignUpResult.ConfirmationRequired(email)
        }
    }

    /** Scambia il codice del link di conferma (PKCE) con una sessione. */
    suspend fun exchangeCode(authCode: String, codeVerifier: String): AuthTokenDto {
        http.requireConfigured()
        return http.client.post("${http.baseUrl}/auth/v1/token") {
            parameter("grant_type", "pkce")
            header("apikey", http.anonKey)
            contentType(ContentType.Application.Json)
            setBody(PkceBody(authCode, codeVerifier))
        }.ensureSuccess().body()
    }

    suspend fun refresh(refreshToken: String): AuthTokenDto {
        http.requireConfigured()
        return http.client.post("${http.baseUrl}/auth/v1/token") {
            parameter("grant_type", "refresh_token")
            header("apikey", http.anonKey)
            contentType(ContentType.Application.Json)
            setBody(RefreshBody(refreshToken))
        }.ensureSuccess().body()
    }

    suspend fun signOut(accessToken: String) {
        http.requireConfigured()
        http.client.post("${http.baseUrl}/auth/v1/logout") {
            header("apikey", http.anonKey)
            header("Authorization", "Bearer $accessToken")
        }
    }
}
