package it.objectfinder.app.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixture: output reale di Mirage (Cactus 4.1.4, lingua "it") su due foto di prova. */
class MirageParseTest {
    private fun fixture(name: String) =
        javaClass.getResource("/mirage/mirage_${name}_it.json")!!.readText()

    @Test
    fun `generic items and low scores are dropped, the main subject comes first`() {
        val s = MirageParse.parse(fixture("cartellini"))
        assertEquals("Scatola di cartone", s.names.first())
        assertFalse(s.names.any { it.lowercase() in setOf("testo", "logo", "tessuto") })
        assertFalse("score 0.317 sotto soglia", s.names.contains("Codice a barre"))
        assertTrue(s.names.size <= 3)
    }

    @Test
    fun `read text keeps confident strings only`() {
        val s = MirageParse.parse(fixture("cartellini"))
        assertEquals(listOf("2023", "MIT", "CLOUDERA"), s.texts.map { it.value })
    }

    @Test
    fun `second photo gives device names`() {
        val s = MirageParse.parse(fixture("sensore"))
        assertTrue(s.names.contains("Smartphone"))
        assertTrue(s.names.contains("Display digitale"))
    }

    @Test
    fun `code-like strings are recognised as serials`() {
        assertTrue(MirageParse.codeLike("SN-12345"))
        assertTrue(MirageParse.codeLike("A1B2C3"))
        assertTrue(MirageParse.codeLike("8001234567890"))
        assertFalse(MirageParse.codeLike("2023"))
        assertFalse(MirageParse.codeLike("CLOUDERA"))
        assertFalse(MirageParse.codeLike("232u"))
    }

    @Test
    fun `broken output gives no suggestions instead of crashing`() {
        assertTrue(MirageParse.parse("non è json").isEmpty)
        assertTrue(MirageParse.parse("{}").isEmpty)
    }

    @Test
    fun `names seen in several photos come first, details drop`() {
        val front = MirageSuggestions(listOf("Statuina di cavallo", "Tavolo"), listOf(ReadText("SN-12345", true)))
        val side = MirageSuggestions(listOf("Mano", "Statuina di cavallo"), listOf(ReadText("sn-12345", true), ReadText("Made in Italy", false)))
        val merged = MirageParse.merge(listOf(front, side))
        assertEquals("Statuina di cavallo", merged.names.first())
        assertEquals(listOf("SN-12345", "Made in Italy"), merged.texts.map { it.value })
    }
}
