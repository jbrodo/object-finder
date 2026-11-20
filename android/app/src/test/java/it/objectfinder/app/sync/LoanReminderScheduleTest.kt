package it.objectfinder.app.sync

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class LoanReminderScheduleTest {
    private val rome = ZoneId.of("Europe/Rome")
    private fun at(h: Int, m: Int) = ZonedDateTime.of(2026, 11, 20, h, m, 0, 0, rome)

    @Test
    fun `next 9 am is today before 9 and tomorrow after`() {
        assertEquals(60 * 60_000L, LoanReminderWorker.millisUntilNext(9, at(8, 0)))
        assertEquals(23 * 60 * 60_000L, LoanReminderWorker.millisUntilNext(9, at(10, 0)))
        assertEquals(24 * 60 * 60_000L, LoanReminderWorker.millisUntilNext(9, at(9, 0)))
    }
}
