package it.objectfinder.app.ui.objects

import it.objectfinder.app.data.local.ObjectEntity
import it.objectfinder.app.data.local.ObjectStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LoanFormatTest {
    private val today = LocalDate.of(2026, 11, 20)

    @Test
    fun `due date reads relative to today`() {
        assertEquals("scade oggi", LoanFormat.due(today, today))
        assertEquals("scade domani", LoanFormat.due(today.plusDays(1), today))
        assertEquals("scade tra 5 giorni", LoanFormat.due(today.plusDays(5), today))
        assertEquals("scade il 30 dic", LoanFormat.due(LocalDate.of(2026, 12, 30), today))
        assertEquals("scaduto ieri", LoanFormat.due(today.minusDays(1), today))
        assertEquals("scaduto da 3 giorni", LoanFormat.due(today.minusDays(3), today))
    }

    @Test
    fun `overdue only after the due day`() {
        assertFalse(LoanFormat.isOverdue(today, today))
        assertTrue(LoanFormat.isOverdue(today.minusDays(1), today))
        assertFalse(LoanFormat.isOverdue(null, today))
        assertEquals("Prestato a Marco · scade domani", LoanFormat.summary("Marco", today.plusDays(1), today))
        assertEquals("Prestato a Marco", LoanFormat.summary("Marco", null, today))
    }

    @Test
    fun `loaned filter shows only current loans`() {
        fun obj(id: String, to: String, status: ObjectStatus = ObjectStatus.ACTIVE) = ObjectEntity(
            id = id, projectId = "p", name = id, category = "", latitude = null, longitude = null,
            accuracyMeters = null, status = status.wire, loanedTo = to, loanedAt = if (to.isBlank()) null else 1L,
        )
        val all = listOf(obj("trapano", "Marco"), obj("scala", ""), obj("bici", "Anna", ObjectStatus.ARCHIVED))
        assertEquals(listOf("trapano"), filterObjects(all, "", ObjectFilter.LOANED).map { it.id })
    }
}
