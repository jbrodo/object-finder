package it.objectfinder.app.data

import it.objectfinder.app.data.local.IdentifierEntity
import it.objectfinder.app.data.local.IdentifierType
import it.objectfinder.app.data.local.ObjectEntity
import it.objectfinder.app.data.local.PhotoSyncStatus
import it.objectfinder.app.data.remote.IdentifierRowDto
import it.objectfinder.app.qr.QrCodes
import it.objectfinder.app.qr.ScanOutcome
import it.objectfinder.app.qr.ScanResolver
import it.objectfinder.app.ui.objects.ObjectFilter
import it.objectfinder.app.ui.objects.filterObjects
import it.objectfinder.app.ui.projects.inviteMessage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentifierTest {
    @Test
    fun `serials are case-insensitive and whitespace-normalized, barcodes keep case`() {
        assertEquals("SN-12 AB", Identifiers.normalize(IdentifierType.SERIAL, "  sn-12   ab "))
        assertEquals("aBc123", Identifiers.normalize(IdentifierType.BARCODE, " aBc123 "))
        assertEquals(listOf("abc", "ABC"), Identifiers.lookupKeys(" abc "))
        assertEquals(listOf("8001234567890"), Identifiers.lookupKeys("8001234567890"))
    }

    private fun local(id: String, status: PhotoSyncStatus) =
        IdentifierEntity(id = id, objectId = "o", type = "barcode", value = "v$id", syncStatus = status.wire)

    @Test
    fun `merge inserts new remote codes and drops codes removed by others`() {
        val plan = IdentifierMerge.plan(
            remote = listOf(IdentifierRowDto("a", "o", "barcode", "va"), IdentifierRowDto("n", "o", "serial", "SN")),
            local = listOf(
                local("a", PhotoSyncStatus.SYNCED),
                local("gone", PhotoSyncStatus.SYNCED),
                local("mine", PhotoSyncStatus.PENDING_UPLOAD),
                local("del", PhotoSyncStatus.PENDING_DELETE),
            ),
        )
        assertEquals(listOf("n"), plan.insertLocally.map { it.id })
        assertEquals(PhotoSyncStatus.SYNCED.wire, plan.insertLocally.single().syncStatus)
        assertEquals(listOf("gone"), plan.removeLocally)
    }

    private fun obj(id: String, project: String, name: String = id) = ObjectEntity(
        id = id, projectId = project, name = name, category = "c",
        latitude = null, longitude = null, accuracyMeters = null,
    )

    private fun resolver(matches: List<ObjectEntity>, current: String = "p1", projects: List<ActiveProject> = emptyList()) =
        ScanResolver(
            findObject = { null },
            findObjectsByCode = { matches },
            currentProjectId = { current },
            knownProjects = { projects },
        )

    @Test
    fun `scanned product code opens the only linked object`() = runTest {
        assertEquals(ScanOutcome.Open("o1"), resolver(listOf(obj("o1", "p1"))).resolve("8001234567890"))
    }

    @Test
    fun `same code on several objects asks which one`() = runTest {
        val outcome = resolver(listOf(obj("o1", "p1"), obj("o2", "p1"), obj("x", "p2"))).resolve("8001234567890")
        assertTrue(outcome is ScanOutcome.Choose)
        assertEquals(listOf("o1", "o2"), (outcome as ScanOutcome.Choose).objects.map { it.id })
    }

    @Test
    fun `code linked in another known project offers switching`() = runTest {
        val other = ActiveProject("p2", "Cantiere", ProjectRole.EDITOR)
        val outcome = resolver(listOf(obj("x", "p2", "Scala")), projects = listOf(other)).resolve("SN1")
        assertEquals(ScanOutcome.SwitchProject(other, "x", "Scala"), outcome)
    }

    @Test
    fun `unlinked code explains how to link it`() = runTest {
        val outcome = resolver(emptyList()).resolve("8001234567890")
        assertTrue(outcome is ScanOutcome.Message && "Collega codice" in outcome.text)
    }

    @Test
    fun `object finder labels still resolve by id, not by linked codes`() = runTest {
        val id = "3f2b8c1e-9a4d-4e6f-8b2a-1c3d5e7f9a0b"
        val r = ScanResolver(
            findObject = { obj(id, "p1") },
            findObjectsByCode = { error("non deve cercare tra i codici") },
            currentProjectId = { "p1" },
            knownProjects = { emptyList() },
        )
        assertEquals(ScanOutcome.Open(id), r.resolve(QrCodes.payloadFor(id)))
    }

    @Test
    fun `list search matches linked codes`() {
        val items = listOf(obj("o1", "p", "Trapano"), obj("o2", "p", "Scala"))
        val codes = mapOf("o2" to listOf("SN-998877"))
        assertEquals(listOf("o2"), filterObjects(items, "9988", ObjectFilter.ALL, codes).map { it.id })
    }

    @Test
    fun `invite message names project, role and email`() {
        val text = inviteMessage("Casa", PendingInvite("i", "new@test.it", ProjectRole.EDITOR))
        assertTrue("«Casa»" in text && "editor" in text && "new@test.it" in text)
    }
}
