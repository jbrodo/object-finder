package it.objectfinder.app.data

import it.objectfinder.app.data.local.PhotoEntity
import it.objectfinder.app.data.local.PhotoSyncStatus
import it.objectfinder.app.data.remote.PhotoRowDto
import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoMergeTest {
    private fun row(id: String) = PhotoRowDto(id = id, objectId = "o", storagePath = "p/o/$id.jpg")
    private fun local(id: String, status: PhotoSyncStatus, path: String? = "/f/$id.jpg") =
        PhotoEntity(id = id, objectId = "o", localPath = path, storagePath = "p/o/$id.jpg", syncStatus = status.wire)

    @Test
    fun `new remote photos are downloaded, known ones skipped`() {
        val plan = PhotoMerge.plan(listOf(row("a"), row("b")), listOf(local("a", PhotoSyncStatus.SYNCED)))
        assertEquals(listOf("b"), plan.download.map { it.id })
        assertEquals(emptyList<String>(), plan.removeLocally)
    }

    @Test
    fun `known photo without local file is downloaded again`() {
        val plan = PhotoMerge.plan(listOf(row("a")), listOf(local("a", PhotoSyncStatus.SYNCED, path = null)))
        assertEquals(listOf("a"), plan.download.map { it.id })
    }

    @Test
    fun `pending local delete is not resurrected by pull`() {
        val plan = PhotoMerge.plan(listOf(row("a")), listOf(local("a", PhotoSyncStatus.PENDING_DELETE, path = null)))
        assertEquals(emptyList<String>(), plan.download.map { it.id })
    }

    @Test
    fun `photo deleted remotely is removed locally only if already synced`() {
        val plan = PhotoMerge.plan(
            remote = emptyList(),
            local = listOf(local("synced", PhotoSyncStatus.SYNCED), local("new", PhotoSyncStatus.PENDING_UPLOAD)),
        )
        assertEquals(listOf("synced"), plan.removeLocally)
    }

    @Test
    fun `remote path matches storage policy layout`() {
        assertEquals("p1/o1/f1.jpg", PhotoRepository.remotePath("p1", "o1", "f1"))
    }

    @Test
    fun `image sampling and target size keep long side within limit`() {
        assertEquals(2, ImageProcessor.sampleSize(4000, 3000, 1920))
        assertEquals(1, ImageProcessor.sampleSize(1920, 1080, 1920))
        assertEquals(4, ImageProcessor.sampleSize(8000, 6000, 1920))
        assertEquals(1920 to 1440, ImageProcessor.targetSize(2000, 1500, 1920))
        assertEquals(1440 to 1920, ImageProcessor.targetSize(1500, 2000, 1920))
        assertEquals(800 to 600, ImageProcessor.targetSize(800, 600, 1920))
    }
}
