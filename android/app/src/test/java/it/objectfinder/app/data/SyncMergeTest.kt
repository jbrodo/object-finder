package it.objectfinder.app.data

import it.objectfinder.app.data.local.ObjectEntity
import it.objectfinder.app.data.local.SyncStatus
import it.objectfinder.app.data.remote.ObjectReadDto
import it.objectfinder.app.data.remote.parseSupabaseError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncMergeTest {
    private fun remote(id: String = "o1", updatedAt: String = "2026-11-19T10:00:00.123456+00:00") = ObjectReadDto(
        id = id, projectId = "p1", name = "Trapano", category = "attrezzi", notes = "garage",
        status = "maintenance", latitude = 45.0, longitude = 9.0, accuracyMeters = 5f,
        version = 3, createdAt = "2026-11-01T08:00:00+00:00", updatedAt = updatedAt,
    )

    private fun local(sync: SyncStatus) = ObjectEntity(
        id = "o1", projectId = "p1", name = "Vecchio nome", category = "x",
        latitude = null, longitude = null, accuracyMeters = null,
        syncStatus = sync.wire, createdAt = 42L, updatedAt = 43L,
    )

    @Test
    fun `pending local edit wins over remote`() {
        assertNull(SyncMerge.mergeRemote(remote(), local(SyncStatus.PENDING)))
    }

    @Test
    fun `remote overwrites synced local and keeps local createdAt`() {
        val merged = SyncMerge.mergeRemote(remote(), local(SyncStatus.SYNCED))!!
        assertEquals("Trapano", merged.name)
        assertEquals("maintenance", merged.status)
        assertEquals(SyncStatus.SYNCED.wire, merged.syncStatus)
        assertEquals(42L, merged.createdAt)
        assertEquals("2026-11-19T10:00:00.123456+00:00", merged.remoteUpdatedAt)
        assertFalse(merged.isPending)
    }

    @Test
    fun `new remote object is inserted with server timestamps`() {
        val merged = SyncMerge.mergeRemote(remote(), null)!!
        assertEquals(SyncMerge.parseEpochMillis("2026-11-01T08:00:00+00:00"), merged.createdAt)
        assertEquals(SyncMerge.parseEpochMillis("2026-11-19T10:00:00.123456+00:00"), merged.updatedAt)
    }

    @Test
    fun `cursor is chronological max, not lexicographic`() {
        // Lessicograficamente "…T10…+02:00" > "…T09…+00:00", ma cronologicamente è prima.
        val earlier = "2026-11-19T10:00:00+02:00" // 08:00Z
        val later = "2026-11-19T09:00:00+00:00"   // 09:00Z
        assertEquals(later, SyncMerge.nextCursor(null, listOf(remote(updatedAt = later), remote(updatedAt = earlier)))?.updatedAt)
    }

    @Test
    fun `cursor keeps current value on empty page`() {
        val current = SyncMerge.PullCursor("2026-11-19T09:00:00+00:00", "o9")
        assertEquals(current, SyncMerge.nextCursor(current, emptyList()))
        assertNull(SyncMerge.nextCursor(null, emptyList()))
    }

    @Test
    fun `cursor uses microsecond precision`() {
        val a = "2026-11-19T09:00:00.000001+00:00"
        val b = "2026-11-19T09:00:00.000002+00:00"
        assertEquals(b, SyncMerge.nextCursor(SyncMerge.PullCursor(a), listOf(remote(updatedAt = b)))?.updatedAt)
    }

    @Test
    fun `write dto carries nullable coordinates to clear location`() {
        val dto = SyncMerge.toWriteDto(local(SyncStatus.PENDING))
        assertNull(dto.latitude)
        assertNull(dto.longitude)
        assertEquals("p1", dto.projectId)
    }

    @Test
    fun `session expiry honours safety margin`() {
        val session = Session("a", "r", expiresAtEpochSec = 1_000, userId = "u", email = "e")
        assertFalse(session.isExpiring(nowEpochSec = 900))
        assertTrue(session.isExpiring(nowEpochSec = 941))
        assertTrue(session.isExpiring(nowEpochSec = 1_200))
    }

    @Test
    fun `supabase error messages are extracted`() {
        assertEquals(
            "Invalid login credentials",
            parseSupabaseError("""{"error":"invalid_grant","error_description":"Invalid login credentials"}"""),
        )
        assertEquals(
            "new row violates row-level security policy",
            parseSupabaseError("""{"code":"42501","message":"new row violates row-level security policy"}"""),
        )
        assertEquals("Email not confirmed", parseSupabaseError("""{"code":400,"msg":"Email not confirmed"}"""))
        assertNull(parseSupabaseError("<html>bad gateway</html>"))
    }

    @Test
    fun `place travels both ways and counts as a real difference`() {
        val server = remote().copy(place = "Garage, scaffale alto")
        val merged = SyncMerge.mergeRemote(server, null)!!
        assertEquals("Garage, scaffale alto", merged.place)
        assertEquals("Garage, scaffale alto", SyncMerge.toWriteDto(merged).place)
        val moved = merged.copy(place = "Cantina")
        assertTrue(!SyncMerge.sameContent(moved, server))
        assertEquals(listOf("Dove"), SyncMerge.diff(moved, server).map { it.label })
    }

    @Test
    fun `same timestamp across a page boundary is ordered by id`() {
        val at = "2026-11-19T09:00:00.5+00:00"
        val next = SyncMerge.nextCursor(null, listOf(remote(id = "a", updatedAt = at), remote(id = "c", updatedAt = at)))
        assertEquals(SyncMerge.PullCursor(at, "c"), next)
        assertEquals(
            "(updated_at.gt.\"$at\",and(updated_at.eq.\"$at\",id.gt.c))",
            it.objectfinder.app.data.remote.SupabaseRestApi.keysetAfter(next!!),
        )
    }

    @Test
    fun `cursor round trips and accepts the old timestamp-only format`() {
        val c = SyncMerge.PullCursor("2026-11-19T09:00:00+00:00", "o1")
        assertEquals(c, SyncMerge.PullCursor.decode(c.encode()))
        assertEquals(SyncMerge.PullCursor("2026-11-19T09:00:00+00:00", ""), SyncMerge.PullCursor.decode("2026-11-19T09:00:00+00:00"))
        assertNull(SyncMerge.PullCursor.decode(null))
    }

    @Test
    fun `new pull starts two minutes before the cursor`() {
        val back = SyncMerge.withLookback(SyncMerge.PullCursor("2026-11-19T09:00:00Z", "o1"))
        assertEquals(SyncMerge.PullCursor("2026-11-19T08:58Z", ""), back)
        assertEquals("2026-11-19T08:58Z", SyncMerge.lookbackTimestamp("2026-11-19T09:00:00Z"))
        assertNull(SyncMerge.withLookback(null))
    }

    @Test
    fun `rows reread in the lookback window are not applied twice`() {
        val server = remote()
        val known = SyncMerge.mergeRemote(server, null)!!
        assertNull(SyncMerge.mergeRemote(server, known))
        assertNotNull(SyncMerge.mergeRemote(server.copy(version = 4, updatedAt = "2026-11-19T11:00:00+00:00"), known))
    }

    @Test
    fun `manual location source syncs and shows in conflicts`() {
        val server = remote().copy(locationSource = "manual", accuracyMeters = null)
        val merged = SyncMerge.mergeRemote(server, null)!!
        assertTrue(merged.isManualLocation)
        assertEquals("manual", SyncMerge.toWriteDto(merged).locationSource)
        val gps = merged.copy(locationSource = "gps")
        assertTrue(!SyncMerge.sameContent(gps, server))
        assertEquals("45.00000, 9.00000 (a mano)", SyncMerge.diff(gps, server).single { it.label == "Posizione" }.theirs)
    }

    @Test
    fun `loan travels with the object and is a real difference`() {
        val server = remote().copy(loanedTo = "Marco", loanedAt = "2026-11-20T10:00:00Z", loanDue = "2026-11-30")
        val merged = SyncMerge.mergeRemote(server, null)!!
        assertTrue(merged.isLoaned)
        assertEquals("2026-11-30", merged.loanDue)
        val dto = SyncMerge.toWriteDto(merged)
        assertEquals("Marco", dto.loanedTo)
        assertEquals("2026-11-30", dto.loanDue)
        assertEquals(java.time.Instant.parse("2026-11-20T10:00:00Z").toString(), dto.loanedAt)
        val returned = merged.copy(loanedTo = "", loanedAt = null, loanDue = null)
        assertEquals(listOf("Prestito"), SyncMerge.diff(returned, server).map { it.label })
    }
}
