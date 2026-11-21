package it.objectfinder.app.data

import android.util.Log
import io.ktor.client.plugins.HttpRequestTimeoutException
import it.objectfinder.app.data.remote.SupabaseException
import it.objectfinder.app.data.remote.isLocalizedServerMessage
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

private const val TAG = "ObjectFinder"

/**
 * Frase per l'utente da un errore: italiano, con il rimedio quando c'è. Il testo tecnico
 * dell'eccezione (spesso in inglese: "Unable to resolve host…") va solo nei log.
 * [fallback] è la frase da usare quando l'errore non dice nulla di utile ("Export non riuscito: riprova").
 */
fun Throwable.userMessage(fallback: String): String {
    Log.w(TAG, fallback, this)
    return when (this) {
        is SupabaseException.NotAuthenticated -> "Sessione scaduta: accedi di nuovo"
        is SupabaseException.NotConfigured -> "Sincronizzazione non disponibile in questa versione dell'app"
        is SupabaseException.Http -> when {
            message?.let(::isLocalizedServerMessage) == true -> message!!
            status == 429 -> "Troppe richieste: attendi un minuto e riprova"
            status >= 500 -> "Il server non risponde: riprova tra poco"
            else -> fallback
        }
        is UnknownHostException, is ConnectException, is NoRouteToHostException ->
            "Nessuna connessione: riprova quando sei online"
        is SocketTimeoutException, is HttpRequestTimeoutException -> "Il server non risponde: riprova tra poco"
        is IOException ->
            if (message.orEmpty().contains("ENOSPC") || message.orEmpty().contains("No space", ignoreCase = true))
                "Spazio esaurito sul telefono: libera spazio e riprova"
            else fallback
        // Lanciate dal nostro codice (error()/check()) con frasi già scritte per l'utente.
        is IllegalStateException, is IllegalArgumentException -> message?.takeIf { it.isNotBlank() } ?: fallback
        else -> fallback
    }
}
