package it.objectfinder.app.data

import it.objectfinder.app.data.local.ObjectEntity
import it.objectfinder.app.data.local.PlaceEntity
import it.objectfinder.app.ui.objects.ObjectFilter
import it.objectfinder.app.ui.objects.filterObjects
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceTreeTest {
    private fun p(id: String, parent: String?, name: String) = PlaceEntity(id, "proj", parent, name)
    private val casa = p("casa", null, "Casa")
    private val garage = p("garage", "casa", "Garage")
    private val scaffale = p("scaffale", "garage", "Scaffale")
    private val cucina = p("cucina", "casa", "cucina")
    private val ufficio = p("ufficio", null, "Ufficio")
    private val tree = PlaceTree(listOf(scaffale, ufficio, garage, casa, cucina))

    @Test
    fun `path joins ancestors from the top`() {
        assertEquals("Casa › Garage › Scaffale", tree.path("scaffale"))
        assertEquals("Ufficio", tree.path("ufficio"))
        assertEquals("", tree.path(null))
        assertEquals("", tree.path("sparito"))
    }

    @Test
    fun `subtree contains the place and everything inside`() {
        assertEquals(setOf("casa", "garage", "scaffale", "cucina"), tree.subtree("casa"))
        assertEquals(setOf("scaffale"), tree.subtree("scaffale"))
    }

    @Test
    fun `flattened is depth first and alphabetical ignoring case`() {
        assertEquals(
            listOf("casa" to 0, "cucina" to 1, "garage" to 1, "scaffale" to 2, "ufficio" to 0),
            tree.flattened().map { it.first.id to it.second },
        )
    }

    @Test
    fun `orphans stay visible and cycles do not hang`() {
        val orphan = p("o", "deleted", "Orfano")
        val a = p("a", "b", "A")
        val b = p("b", "a", "B")
        val t = PlaceTree(listOf(orphan, a, b))
        assertTrue(t.flattened().any { it.first.id == "o" })
        assertEquals(2, t.chain("a").size)
        assertEquals(setOf("a", "b"), t.subtree("a"))
    }

    @Test
    fun `cannot move a place inside itself`() {
        assertFalse(tree.canMove("casa", "scaffale"))
        assertFalse(tree.canMove("casa", "casa"))
        assertTrue(tree.canMove("scaffale", "ufficio"))
        assertTrue(tree.canMove("garage", null))
    }

    @Test
    fun `parents are sent before children`() {
        val order = PlaceTree.parentsFirst(listOf(scaffale, garage, casa)).map { it.id }
        assertTrue(order.indexOf("casa") < order.indexOf("garage"))
        assertTrue(order.indexOf("garage") < order.indexOf("scaffale"))
        assertEquals(3, order.size)
    }

    @Test
    fun `list filter by place includes sub places and search matches the path`() {
        fun o(id: String, place: String?) = ObjectEntity(id = id, projectId = "proj", name = id, category = "", latitude = null, longitude = null, accuracyMeters = null, placeId = place)
        val all = listOf(o("trapano", "scaffale"), o("pentola", "cucina"), o("pc", "ufficio"), o("chiavi", null))
        val inGarage = filterObjects(all, "", ObjectFilter.ALL, within = tree.subtree("garage"))
        assertEquals(listOf("trapano"), inGarage.map { it.id })
        val inCasa = filterObjects(all, "", ObjectFilter.ALL, within = tree.subtree("casa"))
        assertEquals(listOf("trapano", "pentola"), inCasa.map { it.id })
        val paths = all.mapNotNull { x -> x.placeId?.let { it to tree.path(it) } }.toMap()
        assertEquals(listOf("trapano"), filterObjects(all, "garage", ObjectFilter.ALL, placePaths = paths).map { it.id })
    }
}
