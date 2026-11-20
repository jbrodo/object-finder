// Edge Function `identify-plant`: riconoscimento di una pianta con l'API Pl@ntNet.
// L'app manda una foto (corpo della richiesta, image/jpeg) e riceve fino a 5 specie.
//
// Sicurezza e costi:
// - la chiave Pl@ntNet resta qui (secret PLANTNET_API_KEY), mai nell'app;
// - il chiamante è verificato (GET /auth/v1/user con il suo token);
// - quota giornaliera per utente e totale (migration 017, plant_quota_take) per non esaurire le
//   500 identificazioni gratuite al giorno dell'intera app;
// - la foto non viene salvata: passa a Pl@ntNet e basta.
//
// Senza PLANTNET_API_KEY risponde 503 not_configured e l'app usa il modello sul telefono.
// Facoltativi: PLANT_USER_DAILY (default 20), PLANT_GLOBAL_DAILY (default 450), PLANTNET_PROJECT (default "all").

import { checkImage, parseResults, plantNetUrl } from "./logic.ts";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
const ANON_KEY = Deno.env.get("SUPABASE_ANON_KEY") ?? SERVICE_KEY;
const PLANTNET_API_KEY = Deno.env.get("PLANTNET_API_KEY") ?? "";
const PROJECT = Deno.env.get("PLANTNET_PROJECT") ?? "all";
const USER_DAILY = Number(Deno.env.get("PLANT_USER_DAILY") ?? "20");
const GLOBAL_DAILY = Number(Deno.env.get("PLANT_GLOBAL_DAILY") ?? "450");

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
const fail = (status: number, code: string, message: string) => json(status, { code, message });

function serviceHeaders(): Record<string, string> {
  const headers: Record<string, string> = { apikey: SERVICE_KEY, "content-type": "application/json" };
  if (SERVICE_KEY.split(".").length === 3) headers.Authorization = `Bearer ${SERVICE_KEY}`;
  return headers;
}

Deno.serve(async (req) => {
  if (req.method !== "POST") return fail(405, "method", "Usa POST");
  if (!SUPABASE_URL || !SERVICE_KEY) return fail(500, "server", "Funzione non configurata (Supabase)");
  if (!PLANTNET_API_KEY) return fail(503, "not_configured", "Riconoscimento online non configurato");

  // 1. Chi chiama.
  const token = req.headers.get("Authorization")?.replace(/^Bearer\s+/i, "") ?? "";
  if (!token) return fail(401, "auth", "Accesso richiesto");
  const userResponse = await fetch(`${SUPABASE_URL}/auth/v1/user`, {
    headers: { apikey: ANON_KEY, Authorization: `Bearer ${token}` },
  });
  if (!userResponse.ok) return fail(401, "auth", "Sessione non valida");
  const user = await userResponse.json() as { id: string };

  // 2. La foto.
  const bytes = new Uint8Array(await req.arrayBuffer());
  const kind = checkImage(bytes);
  if (kind === "too_large") return fail(413, "image", "Foto troppo grande");
  if (kind !== "jpeg" && kind !== "png") return fail(400, "image", "Serve una foto JPEG o PNG");

  // 3. Quota (prima di chiamare Pl@ntNet: una richiesta rifiutata non consuma nulla).
  const quota = await fetch(`${SUPABASE_URL}/rest/v1/rpc/plant_quota_take`, {
    method: "POST",
    headers: serviceHeaders(),
    body: JSON.stringify({ p_user: user.id, p_user_limit: USER_DAILY, p_global_limit: GLOBAL_DAILY }),
  });
  if (!quota.ok) {
    console.error("plant_quota_take", quota.status, await quota.text());
    return fail(500, "server", "Quota non disponibile");
  }
  const remaining = await quota.json() as number;
  if (remaining < 0) return fail(429, "quota", "Limite giornaliero raggiunto: riprova domani");

  // 4. Pl@ntNet.
  const form = new FormData();
  form.append("images", new Blob([bytes], { type: kind === "jpeg" ? "image/jpeg" : "image/png" }), `photo.${kind === "jpeg" ? "jpg" : "png"}`);
  form.append("organs", "auto");
  const response = await fetch(plantNetUrl(PLANTNET_API_KEY, PROJECT), { method: "POST", body: form });
  if (response.status === 404) return json(200, { results: [], remaining });
  if (response.status === 429) return fail(429, "quota", "Limite giornaliero di Pl@ntNet raggiunto: riprova domani");
  if (!response.ok) {
    console.error("plantnet", response.status, (await response.text()).slice(0, 300));
    return fail(502, "upstream", "Pl@ntNet non disponibile");
  }
  return json(200, { results: parseResults(await response.json()), remaining });
});
