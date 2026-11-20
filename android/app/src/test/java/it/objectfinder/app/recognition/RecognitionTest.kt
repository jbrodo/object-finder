package it.objectfinder.app.recognition

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionTest {
    private fun v(vararg x: Float) = x

    @Test
    fun `cosine similarity`() {
        assertEquals(1f, Recognition.cosine(v(1f, 0f), v(2f, 0f)), 1e-6f)
        assertEquals(0f, Recognition.cosine(v(1f, 0f), v(0f, 1f)), 1e-6f)
        assertEquals(0f, Recognition.cosine(v(1f, 0f), v(1f, 0f, 0f)), 0f) // dimensioni diverse
    }

    @Test
    fun `best photo of each object counts, unrelated objects are dropped`() {
        val query = v(1f, 0f, 0f)
        val photos = listOf(
            "trapano" to v(0.9f, 0.1f, 0f),   // molto simile
            "trapano" to v(0f, 1f, 0f),       // altra foto, diversa: non abbassa il punteggio
            "scala" to v(0.6f, 0.8f, 0f),     // simile
            "bici" to v(0f, 0f, 1f),          // nulla a che vedere
        )
        val ranked = Recognition.rank(query, photos, distanceOf = { null })
        assertEquals(listOf("trapano", "scala"), ranked.map { it.objectId })
        assertEquals(Confidence.HIGH, ranked[0].confidence)
    }

    @Test
    fun `nearby object wins among look-alikes but distance never beats a clearly better match`() {
        val query = v(1f, 0f)
        val lookAlikes = listOf("L1" to v(0.95f, 0.31f), "L2" to v(0.95f, 0.31f))
        val ranked = Recognition.rank(query, lookAlikes, distanceOf = { if (it == "L2") 5.0 else 400.0 })
        assertEquals("L2", ranked.first().objectId) // stessi paletti: vince quello dove sei

        val clear = listOf("giusto" to v(1f, 0f), "vicino" to v(0.6f, 0.8f))
        val ranked2 = Recognition.rank(query, clear, distanceOf = { if (it == "vicino") 0.0 else 1000.0 })
        assertEquals("giusto", ranked2.first().objectId)
    }

    @Test
    fun `vectors survive storage`() {
        val vector = floatArrayOf(0.25f, -1.5f, 3f)
        assertArrayEquals(vector, Recognition.decode(Recognition.encode(vector)), 0f)
        assertTrue(Recognition.encode(vector).size == 12)
    }
}
