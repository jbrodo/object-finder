package it.objectfinder.app.qr

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import it.objectfinder.app.data.ActiveProject
import it.objectfinder.app.data.ProjectRole
import it.objectfinder.app.data.local.ObjectEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCodesTest {
    private val id = "3f2b8c1e-9a4d-4e6f-8b2a-1c3d5e7f9a0b"

    @Test
    fun `payload round trips through parser`() {
        assertEquals("objectfinder://object/$id", QrCodes.payloadFor(id))
        assertEquals(id, QrCodes.parseObjectId(QrCodes.payloadFor(id)))
    }

    @Test
    fun `parser tolerates case, whitespace, trailing slash and query`() {
        assertEquals(id, QrCodes.parseObjectId("  OBJECTFINDER://object/${id.uppercase()}/ "))
        assertEquals(id, QrCodes.parseObjectId("objectfinder://object/$id?src=label"))
    }

    @Test
    fun `parser rejects foreign or malformed codes`() {
        assertNull(QrCodes.parseObjectId("https://example.com/object/$id"))
        assertNull(QrCodes.parseObjectId("objectfinder://object/not-a-uuid"))
        assertNull(QrCodes.parseObjectId("objectfinder://object/$id/../x"))
        assertNull(QrCodes.parseObjectId("8001234567890"))
        assertNull(QrCodes.parseObjectId(null))
    }

    @Test
    fun `generated matrix decodes back to the payload`() {
        val modules = QrCodes.matrix(QrCodes.payloadFor(id), margin = 4)
        val scale = 6
        val side = modules.size * scale
        val pixels = IntArray(side * side) { i ->
            val x = (i % side) / scale
            val y = (i / side) / scale
            if (modules[y][x]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels)))
        assertEquals(QrCodes.payloadFor(id), QRCodeReader().decode(bitmap).text)
    }

    private fun entity(projectId: String) = ObjectEntity(
        id = id, projectId = projectId, name = "Trapano", category = "x",
        latitude = null, longitude = null, accuracyMeters = null,
    )

    private fun resolver(found: ObjectEntity?, current: String, projects: List<ActiveProject> = emptyList()) =
        ScanResolver(findObject = { found }, currentProjectId = { current }, knownProjects = { projects })

    @Test
    fun `resolver opens objects of the active project`() = runTest {
        val outcome = resolver(entity("p1"), "p1").resolve(QrCodes.payloadFor(id))
        assertEquals(ScanOutcome.Open(id), outcome)
    }

    @Test
    fun `resolver offers switching to another known project`() = runTest {
        val other = ActiveProject("p2", "Cantiere", ProjectRole.EDITOR)
        val outcome = resolver(entity("p2"), "p1", listOf(other)).resolve(QrCodes.payloadFor(id))
        assertEquals(ScanOutcome.SwitchProject(other, id, "Trapano"), outcome)
    }

    @Test
    fun `resolver explains unknown codes and missing objects`() = runTest {
        assertTrue(resolver(null, "p1").resolve("hello") is ScanOutcome.Message)
        assertTrue(resolver(null, "p1").resolve(QrCodes.payloadFor(id)) is ScanOutcome.Message)
        assertTrue(resolver(entity("p9"), "p1").resolve(QrCodes.payloadFor(id)) is ScanOutcome.Message)
    }
}
