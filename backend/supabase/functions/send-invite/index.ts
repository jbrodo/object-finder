// Edge Function `send-invite`: manda l'email a chi è stato invitato in un progetto senza avere
// ancora un account. La chiama l'app (owner/admin) dopo invite_project_member → 'invited'.
//
// Sicurezza:
// - il chiamante è verificato qui (GET /auth/v1/user con il suo token), non solo dal gateway;
// - deve essere owner o admin del progetto e l'invito deve esistere ed essere aperto;
// - l'indirizzo del destinatario viene dal database, mai dal client: l'app non può far
//   spedire email arbitrarie;
// - limiti anti-spam: un reinvio ogni 10 minuti per invito, 50 email al giorno per progetto.
//
// Secret richiesti (supabase secrets set): RESEND_API_KEY, INVITE_FROM ("Object Finder <inviti@tuodominio.it>").
// Facoltativo: APP_DOWNLOAD_URL (default: ultima release su GitHub).

import { buildEmail, decide, type Invite } from "./logic.ts";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
const ANON_KEY = Deno.env.get("SUPABASE_ANON_KEY") ?? SERVICE_KEY;
const RESEND_API_KEY = Deno.env.get("RESEND_API_KEY") ?? "";
const INVITE_FROM = Deno.env.get("INVITE_FROM") ?? "";
const DOWNLOAD_URL = Deno.env.get("APP_DOWNLOAD_URL") ??
  "https://github.com/jbrodo/object-finder/releases/latest";

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });

const fail = (status: number, code: string, message: string) => json(status, { code, message });

/** Header per PostgREST con la chiave di servizio (JWT legacy o nuova secret key). */
function serviceHeaders(extra: Record<string, string> = {}): Record<string, string> {
  const headers: Record<string, string> = { apikey: SERVICE_KEY, ...extra };
  if (SERVICE_KEY.split(".").length === 3) headers.Authorization = `Bearer ${SERVICE_KEY}`;
  return headers;
}

async function rest<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(`${SUPABASE_URL}/rest/v1/${path}`, {
    ...init,
    headers: serviceHeaders(init.headers as Record<string, string> | undefined),
  });
  if (!response.ok) throw new Error(`PostgREST ${response.status}: ${await response.text()}`);
  return response.status === 204 ? (undefined as T) : await response.json() as T;
}

const eq = (value: string) => `eq.${encodeURIComponent(value)}`;

Deno.serve(async (req) => {
  if (req.method !== "POST") return fail(405, "method", "Usa POST");
  if (!SUPABASE_URL || !SERVICE_KEY) return fail(500, "server", "Funzione non configurata (Supabase)");
  if (!RESEND_API_KEY || !INVITE_FROM) {
    return fail(503, "not_configured", "Invio email non configurato sul server");
  }

  // 1. Chi chiama.
  const token = req.headers.get("Authorization")?.replace(/^Bearer\s+/i, "") ?? "";
  if (!token) return fail(401, "auth", "Accesso richiesto");
  const userResponse = await fetch(`${SUPABASE_URL}/auth/v1/user`, {
    headers: { apikey: ANON_KEY, Authorization: `Bearer ${token}` },
  });
  if (!userResponse.ok) return fail(401, "auth", "Sessione non valida");
  const user = await userResponse.json() as { id: string; email?: string };

  // 2. Cosa chiede.
  let body: { project_id?: string; email?: string };
  try {
    body = await req.json();
  } catch {
    return fail(400, "body", "Richiesta non valida");
  }
  const projectId = body.project_id ?? "";
  const email = (body.email ?? "").trim().toLowerCase();
  if (!/^[0-9a-f-]{36}$/i.test(projectId) || !email.includes("@")) {
    return fail(400, "body", "Progetto o email mancanti");
  }

  try {
    // 3. Può farlo: owner/admin del progetto.
    const membership = await rest<{ role: string }[]>(
      `project_members?select=role&project_id=${eq(projectId)}&user_id=${eq(user.id)}`,
    );
    if (!membership.some((m) => m.role === "owner" || m.role === "admin")) {
      return fail(403, "forbidden", "Solo proprietario e amministratori possono invitare");
    }

    // 4. L'invito esiste (l'indirizzo viene da qui, non dal client).
    const invites = await rest<(Invite & { email_count: number })[]>(
      `project_invites?select=id,project_id,email,role,accepted_at,last_emailed_at,email_count` +
        `&project_id=${eq(projectId)}&email=${eq(email)}`,
    );
    const invite = invites[0];
    // 409 e non 404: il 404 indica "funzione non pubblicata" (l'app propone la condivisione manuale).
    if (!invite) return fail(409, "no_invite", "Invito non trovato o revocato");

    const since = new Date(Date.now() - 24 * 3600_000).toISOString();
    const recent = await rest<{ id: string }[]>(
      `project_invites?select=id&project_id=${eq(projectId)}&last_emailed_at=gt.${encodeURIComponent(since)}`,
    );
    const decision = decide(invite, recent.length, new Date());
    if (!decision.ok) return fail(decision.status, decision.code, decision.message);

    const project = await rest<{ name: string }[]>(`projects?select=name&id=${eq(projectId)}`);
    const content = buildEmail({
      projectName: project[0]?.name ?? "Object Finder",
      role: invite.role,
      inviterEmail: user.email ?? null,
      inviteeEmail: invite.email,
      downloadUrl: DOWNLOAD_URL,
    });

    // 5. Invio (Resend).
    const sent = await fetch("https://api.resend.com/emails", {
      method: "POST",
      headers: { Authorization: `Bearer ${RESEND_API_KEY}`, "content-type": "application/json" },
      body: JSON.stringify({ from: INVITE_FROM, to: [invite.email], subject: content.subject, text: content.text, html: content.html }),
    });
    if (!sent.ok) {
      console.error("resend", sent.status, await sent.text());
      return fail(502, "send_failed", "Il servizio email non ha accettato l'invio");
    }

    await rest(`project_invites?id=${eq(invite.id)}`, {
      method: "PATCH",
      headers: { "content-type": "application/json", Prefer: "return=minimal" },
      body: JSON.stringify({ last_emailed_at: new Date().toISOString(), email_count: invite.email_count + 1 }),
    });
    return json(200, { sent: true });
  } catch (error) {
    console.error(error);
    return fail(500, "server", "Errore del server durante l'invio");
  }
});
