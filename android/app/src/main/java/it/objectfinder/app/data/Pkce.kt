package it.objectfinder.app.data

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * PKCE (RFC 7636) per il link di conferma email: il telefono tiene il "verifier", Supabase
 * rimanda all'app solo un codice monouso che vale qualcosa solo insieme al verifier. Così un'altra
 * app che registrasse lo stesso schema `objectfinder://` non potrebbe usare il link intercettato.
 */
object Pkce {
    /** Link di ritorno dell'app (da aggiungere ai Redirect URLs di Supabase Auth). */
    const val REDIRECT_URL = "objectfinder://auth/callback"

    private val random = SecureRandom()
    private val urlSafe = Base64.getUrlEncoder().withoutPadding()

    /** 64 caratteri url-safe (ammessi 43–128). */
    fun newVerifier(): String = ByteArray(48).also(random::nextBytes).let(urlSafe::encodeToString)

    /** S256: base64url(sha256(verifier)) senza padding. */
    fun challenge(verifier: String): String =
        urlSafe.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    sealed interface Callback {
        data class Code(val code: String) : Callback
        data class Error(val description: String) : Callback
    }

    /** Legge il ritorno da Supabase: `?code=…` oppure `error_description` (in query o frammento). */
    fun parseCallback(link: String): Callback? {
        if (!link.startsWith(REDIRECT_URL)) return null
        val params = link.substringAfter(REDIRECT_URL).trimStart('/', '?', '#')
            .split('&', '#', '?')
            .mapNotNull { part -> part.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
            .toMap()
        params["code"]?.takeIf { it.isNotBlank() }?.let { return Callback.Code(decode(it)) }
        val error = params["error_description"] ?: params["error"] ?: return Callback.Error("link non valido")
        return Callback.Error(decode(error))
    }

    // Overload con String (API 1): quello con Charset richiede API 33.
    private fun decode(value: String): String = java.net.URLDecoder.decode(value, "UTF-8")
}
