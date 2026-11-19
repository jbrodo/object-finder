# Milestone checklist

## M1 — Android foundation
- [x] Compose application shell (navigazione a tab + editor)
- [x] Room object persistence (schema v2 con migration 1→2)
- [x] Fused location provider wrapper (fix fresco + fallback last known)
- [x] Supabase REST client
- [x] WorkManager sync worker (one-shot dopo modifiche + periodico, vincolo rete)
- [x] Map provider abstraction
- [x] Runtime permission UX hardening (rationale, rifiuto permanente → impostazioni)
- [x] Edit/archive object flows
- [x] Real map screen and provider selector (preferenza persistita, fallback OSM)
- [x] Gradle wrapper, unit test, lint in CI

## M2 — Cloud identity and projects
- [x] Supabase Auth login/signup (email + password, gestione conferma email)
- [x] Default project bootstrap after signup
- [x] Authenticated REST requests (JWT, refresh automatico, retry su 401)
- [x] Sync pull cursor (incrementale su `updated_at`) e regola di conflitto base
- [ ] Create project flow / selezione tra più progetti
- [ ] Membership invite flow
- [ ] Conflitti multi-utente fini: confronto `version` lato server (RPC o Edge Function)
- [ ] Cifratura token con Android Keystore

## M3 — Identification
- [ ] CameraX capture
- [ ] QR scanning
- [ ] NFC read/write policy
- [ ] Private Storage upload (bucket `object-photos`, path `<project>/<object>/<file>`)
- [ ] Visual embeddings and similarity search

## M4 — Tracking
- [ ] BLE scan/connect
- [ ] GPS tracker ingestion endpoint
- [ ] Device credentials and telemetry validation
- [ ] Location retention policies
- [ ] Geofence evaluation and notifications

## Limiti noti
- Il cursore di pull usa `updated_at > cursor`: righe aggiornate nella stessa transazione con
  identico timestamp a cavallo di una pagina da 500 potrebbero essere saltate. Soluzione futura:
  cursore composto `(updated_at, id)`.
- Dopo il logout gli oggetti cloud restano sul dispositivo ma non sono visibili finché non si
  riaccede allo stesso account.
- Nessuna eliminazione definitiva: l'archiviazione è il soft delete previsto per l'MVP.
