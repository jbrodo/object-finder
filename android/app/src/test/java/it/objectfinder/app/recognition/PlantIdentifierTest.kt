package it.objectfinder.app.recognition

import it.objectfinder.app.data.remote.SupabaseException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

class PlantIdentifierTest {
    private val photo = File("foto.jpg")
    private val local = PlantIdentification(PlantSource.OFFLINE, listOf(PlantGuess("Olea europaea", "Olea", "Oleaceae", 0.6f)), true)

    private class FakeOffline(override val isModelReady: Boolean, val result: PlantIdentification?) : OfflinePlants {
        var calls = 0
        override suspend fun identify(photo: File): PlantIdentification? { calls++; return result }
    }

    @Test
    fun `online result wins and keeps common names`() = runBlocking {
        val offline = FakeOffline(true, local)
        val id = PlantIdentifier(offline) { listOf(PlantGuess("Monstera deliciosa", "Monstera", "Araceae", 0.8f, listOf("monstera"))) }
        val out = id.identify(photo) as PlantOutcome.Found
        assertEquals(PlantSource.ONLINE, out.identification.source)
        assertEquals("Monstera", out.identification.guesses.first().displayName)
        assertTrue(out.identification.confident)
        assertEquals(0, offline.calls)
    }

    @Test
    fun `not configured falls back silently and stops asking`() = runBlocking {
        var asked = 0
        val id = PlantIdentifier(FakeOffline(true, local)) { asked++; throw SupabaseException.Http(503, "not_configured") }
        val first = id.identify(photo) as PlantOutcome.Found
        assertEquals(PlantSource.OFFLINE, first.identification.source)
        assertNull(first.note)
        assertFalse(id.onlineAvailable)
        id.identify(photo)
        assertEquals(1, asked)
    }

    @Test
    fun `quota and network errors fall back with an explanation`() = runBlocking {
        val quota = PlantIdentifier(FakeOffline(true, local)) { throw SupabaseException.Http(429, "quota") }
        assertTrue((quota.identify(photo) as PlantOutcome.Found).note!!.contains("Limite"))
        val net = PlantIdentifier(FakeOffline(true, local)) { throw IOException("offline") }
        assertTrue((net.identify(photo) as PlantOutcome.Found).note!!.contains("non raggiungibile"))
        assertTrue(net.onlineAvailable)
    }

    @Test
    fun `no model offline asks to download, keeping the reason`() = runBlocking {
        val out = PlantIdentifier(FakeOffline(false, null)) { throw IOException("offline") }.identify(photo)
        assertTrue(out is PlantOutcome.NeedsModel && out.note != null)
        val signedOut = PlantIdentifier(FakeOffline(false, null), online = null).identify(photo)
        assertEquals(PlantOutcome.NeedsModel(null), signedOut)
    }

    @Test
    fun `empty online answer means not a plant`() = runBlocking {
        assertEquals(PlantOutcome.NotAPlant, PlantIdentifier(FakeOffline(true, local)) { emptyList() }.identify(photo))
    }
}
