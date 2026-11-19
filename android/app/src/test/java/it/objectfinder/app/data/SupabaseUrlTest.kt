package it.objectfinder.app.data

import it.objectfinder.app.data.remote.normalizeSupabaseUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class SupabaseUrlTest {
    @Test
    fun `service paths and trailing slashes are stripped`() {
        val base = "https://abc.supabase.co"
        listOf(
            base, "$base/", " $base/ ", "$base/rest/v1", "$base/rest/v1/", "$base/auth/v1/",
            "$base/storage/v1", "$base/REST/V1/", "$base/rest/v1/rest/v1/",
        ).forEach { assertEquals(it, base, normalizeSupabaseUrl(it)) }
    }

    @Test
    fun `custom domains and paths that are not service paths are kept`() {
        assertEquals("https://api.example.com", normalizeSupabaseUrl("https://api.example.com/"))
        assertEquals("https://example.com/supabase", normalizeSupabaseUrl("https://example.com/supabase/"))
    }
}
