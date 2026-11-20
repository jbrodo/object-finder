package it.objectfinder.app.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlantModelTest {
    @Test
    fun `scientific names lose the author but keep hybrids and subspecies`() {
        assertEquals("Salvia rosmarinus", PlantModel.withoutAuthor("Salvia rosmarinus Spenn."))
        assertEquals("Crepis capillaris", PlantModel.withoutAuthor("Crepis capillaris (L.) Wallr."))
        assertEquals("Mentha × piperita", PlantModel.withoutAuthor("Mentha × piperita L."))
        assertEquals("Olea europaea subsp. cuspidata", PlantModel.withoutAuthor("Olea europaea L. subsp. cuspidata (Wall. ex G.Don) Cif."))
        assertEquals("Rosa", PlantModel.withoutAuthor("Rosa L."))
    }

    @Test
    fun `input is NCHW and normalized like metadata json`() {
        val size = PlantModel.INPUT_SIZE * PlantModel.INPUT_SIZE
        val pixels = IntArray(size) { 0xFF000000.toInt() }
        pixels[0] = 0xFFFF8000.toInt() // r=255 g=128 b=0
        val x = PlantModel.toInput(pixels)
        assertEquals(3 * size, x.size)
        assertEquals((1f - 0.485f) / 0.229f, x[0], 1e-5f)
        assertEquals((128 / 255f - 0.456f) / 0.224f, x[size], 1e-5f)
        assertEquals((0f - 0.406f) / 0.225f, x[2 * size], 1e-5f)
        assertEquals((0f - 0.485f) / 0.229f, x[1], 1e-5f)
    }

    private val labels = listOf(
        PlantLabel("Rosa canina L.", "Rosa", "Rosaceae"),
        PlantLabel("Rosa gallica L.", "Rosa", "Rosaceae"),
        PlantLabel("Rosa moschata Herrm.", "Rosa", "Rosaceae"),
        PlantLabel("Rosa sempervirens L.", "Rosa", "Rosaceae"),
        PlantLabel("Salvia rosmarinus Spenn.", "Salvia", "Lamiaceae"),
        PlantLabel("Olea europaea L.", "Olea", "Oleaceae"),
    )

    @Test
    fun `a clear species is first and confident`() {
        val r = PlantModel.rank(floatArrayOf(0f, 0f, 0f, 0f, 6f, 0f), labels)
        assertEquals("Salvia rosmarinus", r.guesses.first().scientificName)
        assertFalse(r.guesses.first().genusOnly)
        assertTrue(r.confident)
        assertEquals(5, r.guesses.size)
        assertEquals("Specie: Salvia rosmarinus (famiglia Lamiaceae)", r.guesses.first().noteLine)
    }

    @Test
    fun `garden rose spread across wild species becomes the genus`() {
        val r = PlantModel.rank(floatArrayOf(2f, 2f, 2f, 2f, 0f, 0f), labels)
        val first = r.guesses.first()
        assertTrue(first.genusOnly)
        assertEquals("Rosa", first.scientificName)
        assertTrue(first.probability > 0.9f)
        assertTrue(r.confident)
        assertEquals("Genere: Rosa (famiglia Rosaceae)", first.noteLine)
    }

    @Test
    fun `flat output is not confident`() {
        val many = (0 until 200).map { PlantLabel("Genus$it species$it L.", "Genus$it", "F") }
        val r = PlantModel.rank(FloatArray(200), many)
        assertFalse(r.confident)
        assertFalse(r.guesses.first().genusOnly)
    }

    @Test
    fun `a clear lead over the second counts as confident`() {
        // Come la foto d'esempio del modello: 22% contro 4%.
        val many = (0 until 200).map { PlantLabel("Genus$it species$it L.", "Genus$it", "F") }
        val logits = FloatArray(200).also { it[0] = 4.0f; it[1] = 2.3f }
        val r = PlantModel.rank(logits, many)
        assertTrue(r.guesses.first().probability in 0.10f..0.25f)
        assertTrue(r.confident)
    }

    @Test
    fun `labels tsv parses`() {
        val l = PlantModel.parseLabels("Lactuca virosa L.\tLactuca\tAsteraceae\nOlea europaea L.\tOlea\tOleaceae")
        assertEquals(PlantLabel("Olea europaea L.", "Olea", "Oleaceae"), l[1])
    }
}
