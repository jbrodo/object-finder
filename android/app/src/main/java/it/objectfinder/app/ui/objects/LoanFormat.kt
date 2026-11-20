package it.objectfinder.app.ui.objects

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Testi dei prestiti (puri, testabili): scadenza relativa a oggi, in italiano. */
object LoanFormat {
    private val dayMonth = DateTimeFormatter.ofPattern("d MMM", Locale.ITALIAN)

    fun date(day: LocalDate): String = dayMonth.format(day)

    fun due(due: LocalDate, today: LocalDate): String {
        val days = ChronoUnit.DAYS.between(today, due)
        return when {
            days == 0L -> "scade oggi"
            days == 1L -> "scade domani"
            days in 2..6 -> "scade tra $days giorni"
            days > 6 -> "scade il ${date(due)}"
            days == -1L -> "scaduto ieri"
            else -> "scaduto da ${-days} giorni"
        }
    }

    fun isOverdue(due: LocalDate?, today: LocalDate): Boolean = due != null && due.isBefore(today)

    /** "Prestato a Marco · scade tra 3 giorni" (riga della lista e della scheda). */
    fun summary(to: String, due: LocalDate?, today: LocalDate): String =
        "Prestato a $to" + (due?.let { " · ${due(it, today)}" } ?: "")
}
