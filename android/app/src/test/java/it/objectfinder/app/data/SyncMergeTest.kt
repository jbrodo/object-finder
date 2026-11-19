package it.objectfinder.app.data

import it.objectfinder.app.data.local.ObjectEntity
import it.objectfinder.app.data.local.SyncStatus
import it.objectfinder.app.data.remote.ObjectReadDto
import it.objectfinder.app.data.remote.parseSupabaseError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals(later, SyncMerge.nextCursor(null, listOf(remote(updatedAt = later), remote(updatedAt = earlier))))
    }

    @Test
    fun `cursor keeps current value on empty page`() {
        assertEquals("2026-11-19T09:00:00+00:00", SyncMerge.nextCursor("2026-11-19T09:00:00+00:00", emptyList()))
        assertNull(SyncMerge.nextCursor(null, emptyList()))
    }

    @Test
    fun `cursor uses microsecond precision`() {
        val a = "2026-11-19T09:00:00.000001+00:00"
        val b = "2026-11-19T09:00:00.000002+00:00"
        assertEquals(b, SyncMerge.nextCursor(a, listOf(remote(updatedAt = b))))
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
}
