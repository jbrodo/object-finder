package it.objectfinder.app.ui.objects

import it.objectfinder.app.data.Geo
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Formati unici per luogo e tempo: la stessa informazione si legge allo stesso modo in lista,
 * scheda, storico e mappa. L'app è in italiano, quindi le date lo sono anche su telefoni in inglese.
 */
object PlaceFormat {
    private val ITALIAN = Locale.ITALIAN
    private val dayMonthYear = DateTimeFormatter.ofPattern("d MMM yyyy", ITALIAN)
    private val dayMonthYearTime = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", ITALIAN)

    /** "a 40 m da te"; sotto i 10 m la differenza è dentro l'incertezza del GPS. */
    fun distanceFromYou(meters: Double): String =
        if (meters < 10) "qui vicino" else "a ${Geo.formatDistance(meters)} da te"

    /** Coordinate a 5 decimali (≈1 m, più dell'incertezza del GPS) con la precisione. */
    fun coordinates(latitude: Double, longitude: Double, accuracyMeters: Float?): String =
        String.format(Locale.ROOT, "%.5f, %.5f", latitude, longitude) +
            (accuracyMeters?.let { " · ±${it.toInt()} m" } ?: "")

    /** "adesso", "5 min fa", "3 ore fa", "ieri", "4 giorni fa", poi la data. */
    fun age(thenMillis: Long, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val elapsed = nowMillis - thenMillis
        val minutes = elapsed / 60_000
        if (minutes < 1) return "adesso"
        if (minutes < 60) return "$minutes min fa"
        val hours = minutes / 60
        val then = Instant.ofEpochMilli(thenMillis).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val days = ChronoUnit.DAYS.between(then, today)
        return when {
            days == 0L -> if (hours == 1L) "1 ora fa" else "$hours ore fa"
            days == 1L -> "ieri"
            days < 7 -> "$days giorni fa"
            else -> dayMonthYear.format(then)
        }
    }

    /** Data e ora complete, sempre in italiano: "19 nov 2026, 23:51". */
    fun dateTime(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        dayMonthYearTime.format(Instant.ofEpochMilli(millis).atZone(zone))
}
