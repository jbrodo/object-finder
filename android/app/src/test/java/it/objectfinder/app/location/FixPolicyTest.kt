package it.objectfinder.app.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FixPolicyTest {
    private fun fix(accuracy: Float) = DeviceLocation(45.0, 9.0, accuracy)

    @Test
    fun `keeps the most precise fix seen so far`() {
        var best: DeviceLocation? = null
        listOf(40f, 18f, 25f).forEach { best = FixPolicy.better(best, fix(it)) }
        assertEquals(18f, best!!.accuracyMeters)
    }

    @Test
    fun `stops as soon as the fix is good`() {
        assertTrue(FixPolicy.isGoodEnough(fix(8f)))
        assertTrue(FixPolicy.isGoodEnough(fix(10f)))
        assertFalse(FixPolicy.isGoodEnough(fix(12f)))
    }
}
