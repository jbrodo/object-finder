package it.objectfinder.app.nfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NfcTagsTest {
    @Test
    fun `locked or too small tags are never written`() {
        assertEquals(NfcTags.WriteResult.ReadOnly, NfcTags.checkCapacity(writable = false, maxSize = 500, needed = 80))
        assertEquals(NfcTags.WriteResult.TooSmall(80, 46), NfcTags.checkCapacity(writable = true, maxSize = 46, needed = 80))
        assertNull(NfcTags.checkCapacity(writable = true, maxSize = 137, needed = 80)) // NTAG213
    }

    @Test
    fun `every failure tells the user what to do`() {
        listOf(
            NfcTags.WriteResult.ReadOnly,
            NfcTags.WriteResult.TooSmall(80, 46),
            NfcTags.WriteResult.Unsupported,
            NfcTags.WriteResult.Lost,
            NfcTags.WriteResult.Failed("io"),
        ).forEach { result ->
            val text = NfcTags.describe(result)
            assertTrue(text, listOf("usa", "riprova").any { it in text })
        }
    }

    @Test
    fun `tag uid becomes an uppercase hex code`() {
        assertEquals("04A1FF00", NfcSession.uidHex(byteArrayOf(0x04, 0xA1.toByte(), 0xFF.toByte(), 0x00)))
        assertEquals("", NfcSession.uidHex(null))
    }
}
