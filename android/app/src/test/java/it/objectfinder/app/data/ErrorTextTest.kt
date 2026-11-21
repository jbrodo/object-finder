package it.objectfinder.app.data

import it.objectfinder.app.data.remote.SupabaseException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Test

class ErrorTextTest {
    private val fallback = "Export non riuscito: riprova"

    @Test fun offlineSaysSoInsteadOfTheHostName() =
        assertEquals(
            "Nessuna connessione: riprova quando sei online",
            UnknownHostException("Unable to resolve host \"x.supabase.co\"").userMessage(fallback),
        )

    @Test fun timeoutAsksToRetryLater() =
        assertEquals("Il server non risponde: riprova tra poco", SocketTimeoutException("timeout").userMessage(fallback))

    @Test fun knownServerMessageIsKeptTranslated() =
        assertEquals(
            "Email o password non corretti",
            SupabaseException.Http(400, "Email o password non corretti").userMessage(fallback),
        )

    @Test fun unknownServerTextFallsBack() =
        assertEquals(fallback, SupabaseException.Http(400, "Some english server text").userMessage(fallback))

    @Test fun serverErrorAsksToRetryLater() =
        assertEquals("Il server non risponde: riprova tra poco", SupabaseException.Http(503, "Errore HTTP 503").userMessage(fallback))

    @Test fun fullDiskIsNamed() =
        assertEquals(
            "Spazio esaurito sul telefono: libera spazio e riprova",
            IOException("write failed: ENOSPC (No space left on device)").userMessage(fallback),
        )

    @Test fun ourOwnSentencesPassThrough() =
        assertEquals("Il link è scaduto", IllegalStateException("Il link è scaduto").userMessage(fallback))

    @Test fun anythingElseUsesTheFallback() =
        assertEquals(fallback, RuntimeException("NullPointer somewhere").userMessage(fallback))
}
