package it.objectfinder.app.data

import it.objectfinder.app.data.local.LocationHistoryEntity
import it.objectfinder.app.data.local.ObjectEntity
import it.objectfinder.app.ui.auth.PasswordStrength
import it.objectfinder.app.ui.auth.passwordStrength
import it.objectfinder.app.ui.objects.sortByDistance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoAndPasswordTest {
    @Test
    fun `distance matches known reference values`() {
        // Duomo di Milano → Castello Sforzesco ≈ 1.1 km
        val d = Geo.distanceMeters(45.4642, 9.1900, 45.4705, 9.1795)
        assertTrue("$d", d in 1_000.0..1_200.0)
        assertEquals(0.0, Geo.distanceMeters(45.0, 9.0, 45.0, 9.0), 1e-6)
        // Milano → Roma ≈ 477 km
        assertTrue(Geo.distanceMeters(45.4642, 9.1900, 41.9028, 12.4964) in 470_000.0..485_000.0)
    }

    @Test
    fun `distance formatting`() {
        assertEquals("85 m", Geo.formatDistance(85.4))
        assertEquals("1,5 km", Geo.formatDistance(1_520.0))
        assertEquals("42 km", Geo.formatDistance(42_300.0))
    }

    private fun point(lat: Double, lon: Double, acc: Float? = 5f) =
        LocationHistoryEntity("h", "o", lat, lon, acc, 0L)

    @Test
    fun `history ignores GPS noise but records real moves`() {
        assertTrue(Geo.isNewPosition(null, 45.0, 9.0, 5f))
        val here = point(45.0, 9.0)
        assertFalse(Geo.isNewPosition(here, 45.00003, 9.0, 5f))      // ~3 m
        assertFalse(Geo.isNewPosition(here, 45.0003, 9.0, 50f))      // ~33 m ma incertezza 50 m
        assertTrue(Geo.isNewPosition(here, 45.0003, 9.0, 5f))        // ~33 m con buona precisione
    }

    private fun obj(id: String, lat: Double?, lon: Double?) = ObjectEntity(
        id = id, projectId = "p", name = id, category = "c", latitude = lat, longitude = lon, accuracyMeters = null,
    )

    @Test
    fun `nearby sorting puts closest first and objects without location last`() {
        val items = listOf(obj("lontano", 45.10, 9.0), obj("senza", null, null), obj("vicino", 45.001, 9.0))
        assertEquals(listOf("vicino", "lontano", "senza"), sortByDistance(items, 45.0, 9.0).map { it.id })
    }

    @Test
    fun `password strength guidance`() {
        assertEquals(PasswordStrength.WEAK, passwordStrength("abc123"))
        assertEquals(PasswordStrength.WEAK, passwordStrength("abcdefghij"))
        assertEquals(PasswordStrength.FAIR, passwordStrength("abcdef12"))
        assertEquals(PasswordStrength.STRONG, passwordStrength("Tr4pano-Garage"))
    }
}
