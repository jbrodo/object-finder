package it.objectfinder.app.ui.objects

import it.objectfinder.app.maps.MapMarker
import it.objectfinder.app.maps.groupMarkers
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class PlaceFormatTest {
    private val zone = ZoneId.of("Europe/Rome")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private val now = at(2026, 11, 20, 10, 0)

    @Test
    fun `age reads naturally in italian`() {
        assertEquals("adesso", PlaceFormat.age(now - 20_000, now, zone))
        assertEquals("5 min fa", PlaceFormat.age(now - 5 * 60_000, now, zone))
        assertEquals("1 ora fa", PlaceFormat.age(now - 70 * 60_000, now, zone))
        assertEquals("3 ore fa", PlaceFormat.age(at(2026, 11, 20, 7, 0), now, zone))
        assertEquals("ieri", PlaceFormat.age(at(2026, 11, 19, 23, 51), now, zone))
        assertEquals("4 giorni fa", PlaceFormat.age(at(2026, 11, 16, 9, 0), now, zone))
        assertEquals("2 nov 2026", PlaceFormat.age(at(2026, 11, 2, 9, 0), now, zone))
    }

    @Test
    fun `dates are italian whatever the phone language`() {
        assertEquals("19 nov 2026, 23:51", PlaceFormat.dateTime(at(2026, 11, 19, 23, 51), zone))
    }

    @Test
    fun `coordinates use one precision everywhere`() {
        assertEquals("45.46420, 9.18998 · ±5 m", PlaceFormat.coordinates(45.4642035, 9.189982, 5.6f))
        assertEquals("45.46420, 9.18998", PlaceFormat.coordinates(45.4642035, 9.189982, null))
    }

    @Test
    fun `objects in the same spot share one map pin`() {
        val markers = listOf(
            MapMarker("a", "Scala", "", 45.464198, 9.189980),
            MapMarker("b", "Bici", "", 45.464210, 9.189990), // ~1.5 m
            MapMarker("c", "Trapano", "", 45.470000, 9.190000), // ~650 m
        )
        val groups = groupMarkers(markers)
        assertEquals(2, groups.size)
        assertEquals("2 oggetti", groups[0].label)
        assertEquals("Trapano", groups[1].label)
    }

    @Test
    fun `group circle uses the worst gps accuracy and ignores manual points`() {
        val group = groupMarkers(listOf(
            MapMarker("a", "Scala", "", 45.0, 9.0, accuracyMeters = 12f),
            MapMarker("b", "Bici", "", 45.0, 9.0, accuracyMeters = 40f, approximate = true),
            MapMarker("c", "Trapano", "", 45.0, 9.0, manual = true),
        )).single()
        assertEquals(40f, group.uncertaintyMeters)
        assertEquals(false, group.approximate) // non tutti approssimati: segnaposto pieno
        assertEquals("approssimata ±40 m", it.objectfinder.app.maps.markerDetail(group.items[1]))
        assertEquals("indicata a mano", it.objectfinder.app.maps.markerDetail(group.items[2]))
    }
}
