package it.objectfinder.app.data

import it.objectfinder.app.data.local.ObjectEntity
import it.objectfinder.app.data.local.SyncStatus
import it.objectfinder.app.data.remote.ObjectReadDto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.GeneralSecurityException
import javax.crypto.KeyGenerator

class ConflictAndCryptoTest {
    private fun local(name: String = "Trapano", remoteUpdatedAt: String? = "2026-11-19T10:00:00+00:00", status: SyncStatus = SyncStatus.PENDING) =
        ObjectEntity(
            id = "o", projectId = "p", name = name, category = "attrezzi", notes = "",
            latitude = 45.0, longitude = 9.0, accuracyMeters = 5f,
            syncStatus = status.wire, remoteUpdatedAt = remoteUpdatedAt, remoteVersion = 3,
        )

    private fun server(name: String = "Trapano", updatedAt: String = "2026-11-19T10:00:00+00:00", version: Int = 3) =
        ObjectReadDto(
            id = "o", projectId = "p", name = name, category = "attrezzi", notes = "",
            latitude = 45.0, longitude = 9.0, accuracyMeters = 5f, version = version, updatedAt = updatedAt,
        )

    @Test
    fun `server unchanged since last sync is not a conflict`() {
        // Es. dati della versione precedente dell'app (senza version): stesso updated_at → si riprova.
        val m = SyncMerge.resolveMismatch(local(name = "Trapano Bosch"), server(version = 7))
        assertEquals(SyncMerge.Mismatch.Retry(7), m)
    }

    @Test
    fun `identical content is not a conflict`() {
        val m = SyncMerge.resolveMismatch(local(remoteUpdatedAt = null), server(updatedAt = "2026-11-20T08:00:00+00:00", version = 9))
        assertEquals(SyncMerge.Mismatch.Retry(9), m)
    }

    @Test
    fun `a real concurrent change is a conflict with field differences`() {
        val mine = local(name = "Trapano mio")
        val theirs = server(name = "Trapano loro", updatedAt = "2026-11-20T08:00:00+00:00", version = 4)
        val m = SyncMerge.resolveMismatch(mine, theirs)
        assertTrue(m is SyncMerge.Mismatch.Conflict)
        val diff = SyncMerge.diff(mine, theirs)
        assertEquals(listOf(SyncMerge.FieldDiff("Nome", "Trapano mio", "Trapano loro")), diff)
    }

    @Test
    fun `missing server row means deleted remotely`() {
        assertEquals(SyncMerge.Mismatch.DeletedRemotely, SyncMerge.resolveMismatch(local(), null))
    }

    @Test
    fun `pull never overwrites an open conflict and records the server version`() {
        assertNull(SyncMerge.mergeRemote(server(name = "nuovo"), local(status = SyncStatus.CONFLICT)))
        assertEquals(3, SyncMerge.mergeRemote(server(), local(status = SyncStatus.SYNCED))!!.remoteVersion)
    }

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun `session encryption round trips and uses a fresh iv each time`() {
        val plain = """{"accessToken":"a.b.c"}""".toByteArray()
        val a = AesGcm.seal(key, plain)
        val b = AesGcm.seal(key, plain)
        assertArrayEquals(plain, AesGcm.open(key, a))
        assertTrue(!a.contentEquals(b))
        assertTrue(String(a).indexOf("accessToken") < 0)
    }

    @Test(expected = GeneralSecurityException::class)
    fun `tampered ciphertext is rejected`() {
        val sealed = AesGcm.seal(key, "segreto".toByteArray())
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 1).toByte()
        AesGcm.open(key, sealed)
    }

    @Test(expected = GeneralSecurityException::class)
    fun `another key cannot decrypt`() {
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        AesGcm.open(other, AesGcm.seal(key, "segreto".toByteArray()))
    }
}
