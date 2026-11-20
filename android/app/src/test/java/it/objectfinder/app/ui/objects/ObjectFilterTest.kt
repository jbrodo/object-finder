package it.objectfinder.app.ui.objects

import it.objectfinder.app.data.local.ObjectEntity
import it.objectfinder.app.data.local.ObjectStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ObjectFilterTest {
    private fun obj(name: String, status: ObjectStatus, category: String = "general", notes: String = "") =
        ObjectEntity(
            id = name, projectId = "p", name = name, category = category, notes = notes,
            latitude = null, longitude = null, accuracyMeters = null, status = status.wire,
        )

    private val all = listOf(
        obj("Trapano", ObjectStatus.ACTIVE, category = "Attrezzi"),
        obj("Scala", ObjectStatus.MAINTENANCE, notes = "in cantina"),
        obj("Bici vecchia", ObjectStatus.ARCHIVED),
    )

    @Test
    fun `active filter hides archived but keeps other statuses`() {
        assertEquals(listOf("Trapano", "Scala"), filterObjects(all, "", ObjectFilter.ACTIVE).map { it.name })
    }

    @Test
    fun `archived filter shows only archived`() {
        assertEquals(listOf("Bici vecchia"), filterObjects(all, "", ObjectFilter.ARCHIVED).map { it.name })
    }

    @Test
    fun `query matches name category and notes case-insensitively`() {
        assertEquals(listOf("Trapano"), filterObjects(all, "attrezzi", ObjectFilter.ALL).map { it.name })
        assertEquals(listOf("Scala"), filterObjects(all, "CANTINA", ObjectFilter.ALL).map { it.name })
        assertEquals(emptyList<String>(), filterObjects(all, "bici", ObjectFilter.ACTIVE).map { it.name })
    }

    @Test
    fun `list location line prefers distance and never shows raw coordinates`() {
        val item = obj("x", ObjectStatus.ACTIVE).copy(latitude = 45.4642035, longitude = 9.189982, accuracyMeters = 7.8f)
        assertEquals("Punto GPS salvato · ±7 m", locationLine(item, null))
        assertEquals("a 85 m da te", locationLine(item, 85.4))
        assertEquals("qui vicino", locationLine(item, 4.0))
        assertEquals("Posizione non registrata", locationLine(obj("y", ObjectStatus.ACTIVE), null))
    }

    @Test
    fun `meta line hides default category and status`() {
        assertEquals(null, metaLine(obj("a", ObjectStatus.ACTIVE)))
        assertEquals(null, metaLine(obj("a", ObjectStatus.ACTIVE, category = "Generale")))
        assertEquals("Attrezzi", metaLine(obj("a", ObjectStatus.ACTIVE, category = "Attrezzi")))
        assertEquals("Attrezzi · In manutenzione", metaLine(obj("a", ObjectStatus.MAINTENANCE, category = "Attrezzi")))
    }

    @Test
    fun `query matches the place description`() {
        val inGarage = obj("Trapano", ObjectStatus.ACTIVE).copy(place = "Garage, scaffale alto")
        assertEquals(listOf("Trapano"), filterObjects(listOf(inGarage), "garage", ObjectFilter.ACTIVE).map { it.name })
    }
}
