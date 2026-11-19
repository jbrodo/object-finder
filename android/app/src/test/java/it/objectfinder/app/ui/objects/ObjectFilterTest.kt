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
    fun `location formatting is locale independent`() {
        val item = obj("x", ObjectStatus.ACTIVE).copy(latitude = 45.4642035, longitude = 9.189982, accuracyMeters = 7.8f)
        assertEquals("45.464204, 9.189982 · ±7 m", formatLocation(item))
        assertEquals("Posizione non acquisita", formatLocation(obj("y", ObjectStatus.ACTIVE)))
    }
}
