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
        assertTrue(
            localizeServerMessage("Could not find the 'location_source' column of 'objects' in the schema cache")
                .startsWith("Il server non è ancora aggiornato")
        )
        assertEquals(
            "Permessi insufficienti per questa operazione",
            localizeServerMessage("new row violates row-level security policy for table \"objects\""),
        )
        assertEquals("qualcosa di nuovo", localizeServerMessage("qualcosa di nuovo"))
    }

    @Test
    fun `invite expiry`() {
        val now = 1_000_000L
        assertTrue(PendingInvite("i", "a@b.it", ProjectRole.EDITOR, expiresAt = now - 1).isExpired(now))
        assertTrue(!PendingInvite("i", "a@b.it", ProjectRole.EDITOR, expiresAt = now + 1).isExpired(now))
        assertTrue(!PendingInvite("i", "a@b.it", ProjectRole.EDITOR, expiresAt = null).isExpired(now)) // server vecchio
    }

    @Test
    fun `password errors from supabase are in italian`() {
        assertEquals(
            "La nuova password deve essere diversa da quella attuale",
            localizeServerMessage("New password should be different from the old password."),
        )
        assertEquals(
            "Hai già chiesto un link da poco: attendi un minuto e riprova",
            localizeServerMessage("For security purposes, you can only request this after 42 seconds."),
        )
    }

    @Test
    fun `email link flow defaults to sign up`() {
        assertEquals(EmailLinkFlow.RECOVERY, EmailLinkFlow.fromWire("recovery"))
        assertEquals(EmailLinkFlow.SIGN_UP, EmailLinkFlow.fromWire(null))
    }
}
