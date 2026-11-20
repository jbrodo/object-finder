// Logica pura della funzione send-invite: regole di invio e testo dell'email (testabile con `deno test`).

export const RESEND_AFTER_MINUTES = 10;
export const DAILY_LIMIT_PER_PROJECT = 50;

export type Invite = {
  id: string;
  project_id: string;
  email: string;
  role: string;
  accepted_at: string | null;
  last_emailed_at: string | null;
};

export type Decision =
  | { ok: true }
  | { ok: false; status: number; code: "accepted" | "too_soon" | "daily_limit"; message: string };

/** Si può inviare? Invito ancora aperto, non appena inviato, sotto il limite del progetto. */
export function decide(invite: Invite, sentLast24h: number, now: Date): Decision {
  if (invite.accepted_at) {
    return { ok: false, status: 409, code: "accepted", message: "L'invito è già stato accettato." };
  }
  if (invite.last_emailed_at) {
    const elapsedMin = (now.getTime() - new Date(invite.last_emailed_at).getTime()) / 60_000;
    if (elapsedMin < RESEND_AFTER_MINUTES) {
      return {
        ok: false,
        status: 429,
        code: "too_soon",
        message: `Email già inviata da poco: riprova tra ${Math.ceil(RESEND_AFTER_MINUTES - elapsedMin)} minuti.`,
      };
    }
  }
  if (sentLast24h >= DAILY_LIMIT_PER_PROJECT) {
    return {
      ok: false,
      status: 429,
      code: "daily_limit",
      message: "Limite giornaliero di email di invito raggiunto per questo progetto.",
    };
  }
  return { ok: true };
}

const ROLE_LABELS: Record<string, string> = {
  admin: "amministratore",
  editor: "editor",
  viewer: "sola lettura",
  tracker: "tracker",
};

export function escapeHtml(value: string): string {
  return value
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

export type EmailContent = { subject: string; text: string; html: string };

/** Email in italiano, solo con dati già noti all'invitato (progetto, chi invita, il suo indirizzo). */
export function buildEmail(args: {
  projectName: string;
  role: string;
  inviterEmail: string | null;
  inviteeEmail: string;
  downloadUrl: string;
}): EmailContent {
  const role = ROLE_LABELS[args.role] ?? args.role;
  const who = args.inviterEmail ?? "Un membro del progetto";
  // Il nome del progetto è scelto da un utente: nell'oggetto niente a capo, lunghezza limitata.
  const project = args.projectName.replace(/[\r\n]+/g, " ").slice(0, 80);
  const subject = `${who} ti ha invitato nel progetto «${project}» su Object Finder`;
  const text = [
    `${who} ti ha invitato nel progetto «${project}» su Object Finder, con il ruolo ${role}.`,
    "",
    "Object Finder registra dove hai messo le tue cose (foto, posizione, etichette QR e NFC) e le condivide con il progetto.",
    "",
    `1. Scarica l'app per Android: ${args.downloadUrl}`,
    `2. Registrati con questo indirizzo: ${args.inviteeEmail}`,
    "3. Conferma l'indirizzo dall'email che ricevi alla registrazione.",
    "",
    "Al primo accesso il progetto comparirà da solo. L'invito vale 30 giorni.",
    "",
    "Se non conosci chi ti ha invitato, ignora questa email.",
  ].join("\n");
  const e = escapeHtml;
  const html = `<!doctype html><html lang="it"><body style="font-family:Roboto,Arial,sans-serif;line-height:1.5;color:#1c1b1f;max-width:560px;margin:0 auto;padding:24px">
<p><strong>${e(who)}</strong> ti ha invitato nel progetto <strong>«${e(project)}»</strong> su Object Finder, con il ruolo ${e(role)}.</p>
<p>Object Finder registra dove hai messo le tue cose (foto, posizione, etichette QR e NFC) e le condivide con il progetto.</p>
<ol>
<li><a href="${e(args.downloadUrl)}">Scarica l'app per Android</a></li>
<li>Registrati con questo indirizzo: <strong>${e(args.inviteeEmail)}</strong></li>
<li>Conferma l'indirizzo dall'email che ricevi alla registrazione.</li>
</ol>
<p>Al primo accesso il progetto comparirà da solo. L'invito vale 30 giorni.</p>
<p style="color:#605d66;font-size:13px">Se non conosci chi ti ha invitato, ignora questa email.</p>
</body></html>`;
  return { subject, text, html };
}
