package it.objectfinder.app.data

import it.objectfinder.app.data.remote.MembershipRoleDto
import it.objectfinder.app.data.remote.ProjectMemberDto
import it.objectfinder.app.data.remote.ProjectWithRoleDto
import it.objectfinder.app.data.remote.localizeServerMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectRoleTest {
    @Test
    fun `edit and manage permissions follow server policies`() {
        assertTrue(ProjectRole.OWNER.canEditObjects && ProjectRole.OWNER.canManageMembers)
        assertTrue(ProjectRole.ADMIN.canEditObjects && ProjectRole.ADMIN.canManageMembers)
        assertTrue(ProjectRole.EDITOR.canEditObjects)
        assertFalse(ProjectRole.EDITOR.canManageMembers)
        assertFalse(ProjectRole.VIEWER.canEditObjects)
        assertFalse(ProjectRole.TRACKER.canEditObjects)
        assertFalse(ProjectRole.OWNER in ProjectRole.assignable)
    }

    @Test
    fun `local offline project has no restrictions on objects`() {
        val local = ActiveProject("default-project", "Locale", role = null)
        assertTrue(local.canEditObjects)
        assertFalse(local.canManageMembers)
    }

    @Test
    fun `project dto maps embedded membership role`() {
        val dto = ProjectWithRoleDto("p", "Casa", ownerId = "u", memberships = listOf(MembershipRoleDto("viewer")))
        assertEquals(ProjectRole.VIEWER, dto.toActive().role)
        // Ruolo sconosciuto o assente → il più restrittivo.
        assertEquals(ProjectRole.VIEWER, dto.copy(memberships = emptyList()).toActive().role)
    }

    @Test
    fun `member dto marks current user`() {
        val member = ProjectMemberDto(userId = "me", email = "a@b.it", role = "admin").toMember("me")
        assertTrue(member.isMe)
        assertEquals(ProjectRole.ADMIN, member.role)
        assertEquals("other", ProjectMemberDto(userId = "other", role = "editor").toMember("me").email)
    }

    @Test
    fun `server messages are localized and unknown ones kept`() {
        assertEquals("Nessun account registrato con questa email", localizeServerMessage("no account found for this email"))
        assertEquals("Email o password non corretti", localizeServerMessage("Invalid login credentials"))
        assertEquals(
            "Permessi insufficienti per questa operazione",
            localizeServerMessage("new row violates row-level security policy for table \"objects\""),
        )
        assertEquals("qualcosa di nuovo", localizeServerMessage("qualcosa di nuovo"))
    }
}
