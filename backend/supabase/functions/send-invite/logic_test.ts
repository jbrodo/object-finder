import { assert, assertEquals, assertFalse, assertStringIncludes } from "jsr:@std/assert@1.0.13";
import { buildEmail, DAILY_LIMIT_PER_PROJECT, decide, escapeHtml, type Invite } from "./logic.ts";

const invite = (over: Partial<Invite> = {}): Invite => ({
  id: "i1",
  project_id: "p1",
  email: "nuovo@example.com",
  role: "editor",
  accepted_at: null,
  last_emailed_at: null,
  ...over,
});
const now = new Date("2026-11-20T10:00:00Z");

Deno.test("first email for an open invite is allowed", () => {
  assertEquals(decide(invite(), 0, now), { ok: true });
});

Deno.test("accepted invites are not emailed", () => {
  const d = decide(invite({ accepted_at: "2026-11-19T10:00:00Z" }), 0, now);
  assertFalse(d.ok);
  if (!d.ok) assertEquals(d.code, "accepted");
});

Deno.test("resend waits ten minutes and says how long", () => {
  const d = decide(invite({ last_emailed_at: "2026-11-20T09:56:00Z" }), 1, now);
  assertFalse(d.ok);
  if (!d.ok) {
    assertEquals(d.status, 429);
    assertStringIncludes(d.message, "6 minuti");
  }
  assert(decide(invite({ last_emailed_at: "2026-11-20T09:49:00Z" }), 1, now).ok);
});

Deno.test("daily limit per project stops bulk sending", () => {
  const d = decide(invite(), DAILY_LIMIT_PER_PROJECT, now);
  assertFalse(d.ok);
  if (!d.ok) assertEquals(d.code, "daily_limit");
});

Deno.test("email is italian and escapes user-provided text", () => {
  const mail = buildEmail({
    projectName: "Casa <script>\nalert(1)",
    role: "viewer",
    inviterEmail: "anna@example.com",
    inviteeEmail: "nuovo@example.com",
    downloadUrl: "https://example.com/app",
  });
  assertFalse(mail.subject.includes("\n"));
  assertStringIncludes(mail.subject, "anna@example.com ti ha invitato");
  assertStringIncludes(mail.text, "sola lettura");
  assertStringIncludes(mail.text, "Registrati con questo indirizzo: nuovo@example.com");
  assertFalse(mail.html.includes("<script>"));
  assertStringIncludes(mail.html, "&lt;script&gt;");
});

Deno.test("html escaping covers quotes", () => {
  assertEquals(escapeHtml(`<a href="x">'&'</a>`), "&lt;a href=&quot;x&quot;&gt;&#39;&amp;&#39;&lt;/a&gt;");
});
