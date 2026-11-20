package it.objectfinder.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PkceTest {
    @Test
    fun `challenge matches the RFC 7636 example`() {
        // Appendice B della RFC 7636.
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun `verifier is long, url safe and random`() {
        val a = Pkce.newVerifier()
        assertTrue(a.length in 43..128)
        assertTrue(a.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertNotEquals(a, Pkce.newVerifier())
    }

    @Test
    fun `callback with code, error, or unrelated link`() {
        assertEquals(Pkce.Callback.Code("abc-123"), Pkce.parseCallback("objectfinder://auth/callback?code=abc-123"))
        assertEquals(
            Pkce.Callback.Error("Email link is invalid or has expired"),
            Pkce.parseCallback("objectfinder://auth/callback#error=access_denied&error_code=otp_expired&error_description=Email+link+is+invalid+or+has+expired"),
        )
        assertNull(Pkce.parseCallback("objectfinder://object/0b8e7b6e-0000-4000-8000-000000000001"))
    }
}
