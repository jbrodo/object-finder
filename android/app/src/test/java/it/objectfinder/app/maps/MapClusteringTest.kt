package it.objectfinder.app.maps

import it.objectfinder.app.data.Geo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapClusteringTest {
    private val lat = 45.4642
    private fun at(id: String, northMeters: Double) =
        MapMarker(id, id, "", lat + northMeters / 111_320.0, 9.19)

    @Test
    fun `scale halves at every zoom level`() {
        val z15 = MapScale.metersPerDp(lat, 15.0)
        assertEquals(z15 / 2, MapScale.metersPerDp(lat, 16.0), 1e-9)
        assertEquals(3.36, z15, 0.01) // ~3.4 m per dp a Milano, zoom 15
    }

    @Test
    fun `points split as you zoom in`() {
        val markers = listOf(at("a", 0.0), at("b", 60.0))
        assertEquals(1, groupMarkers(markers, MapScale.clusterRadiusMeters(lat, 15.0)).size) // 60 m < 188 m
        assertEquals(2, groupMarkers(markers, MapScale.clusterRadiusMeters(lat, 18.0)).size) // 60 m > 24 m
    }

    @Test
    fun `tap zooms in when the group can separate, fans out when it cannot`() {
        val far = MarkerGroup(lat, 9.19, listOf(at("a", 0.0), at("b", 60.0)))
        val sameShelf = MarkerGroup(lat, 9.19, listOf(at("a", 0.0), at("b", 2.0), at("c", 4.0)))
        assertTrue(far.canSplitByZooming(15.0))
        assertFalse(sameShelf.canSplitByZooming(15.0))
        assertFalse(far.canSplitByZooming(MapScale.MAX_ZOOM)) // già al massimo: ventaglio
    }

    @Test
    fun `fan places every object at the same screen distance, first one on top`() {
        val group = MarkerGroup(lat, 9.19, listOf(at("a", 0.0), at("b", 1.0), at("c", 2.0), at("d", 3.0)))
        val legs = spiderLegs(group, 19.0)
        val expected = MapScale.SPIDER_DP * MapScale.metersPerDp(lat, 19.0)
        legs.forEach { leg ->
            assertEquals(expected, Geo.distanceMeters(group.latitude, group.longitude, leg.latitude, leg.longitude), expected * 0.01)
        }
        assertTrue(legs[0].latitude > group.latitude) // in alto (nord)
        assertEquals(group.longitude, legs[0].longitude, 1e-9)
        assertTrue(legs[1].longitude > group.longitude) // poi a destra (est), senso orario
        assertEquals(listOf("a", "b", "c", "d"), legs.map { it.item.id })
    }

    @Test
    fun `many objects get a wider fan`() {
        val many = MarkerGroup(lat, 9.19, (1..10).map { at("o$it", 0.0) })
        val d = spiderLegs(many, 19.0).first().let { Geo.distanceMeters(lat, 9.19, it.latitude, it.longitude) }
        assertEquals(160 * MapScale.metersPerDp(lat, 19.0), d, d * 0.01)
    }

    @Test
    fun `zoom step rounds to half levels`() {
        assertEquals(15.5, MapScale.step(15.4), 0.0)
        assertEquals(15.0, MapScale.step(15.2), 0.0)
    }
}
