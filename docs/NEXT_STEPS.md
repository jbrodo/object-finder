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
- [x] Create project flow / selezione tra più progetti
- [x] Membership flow: aggiunta per email, ruoli, rimozione, uscita (RPC migration 004)
- [ ] Inviti a email non ancora registrate (tabella inviti + accettazione al signup)
- [ ] Conflitti multi-utente fini: confronto `version` lato server (RPC o Edge Function)
- [ ] Cifratura token con Android Keystore

## M3 — Identification
- [x] Foto oggetto: scatto (app fotocamera di sistema) e galleria, normalizzazione JPEG
- [ ] CameraX capture in-app (inquadratura guidata per il riconoscimento)
- [ ] QR scanning
- [ ] NFC read/write policy
- [x] Private Storage upload (bucket `object-photos`, path `<project>/<object>/<file>`, migration 005)
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
- `add_project_member` rivela a owner/admin se un'email ha un account (enumerazione limitata
  ai gestori di progetto); da rivedere con il flusso inviti.
- Login, sync e gestione membri sono verificati su Postgres con le migration, ma non ancora
  end-to-end contro un'istanza Supabase reale (GoTrue + PostgREST).
- Le foto vengono scaricate tutte durante la sync (nessun download on-demand né limite di spazio):
  da rivedere per progetti con molte foto.
- Il file picker su emulatore API 30 non mostra file copiati via adb: l'import da galleria è stato
  verificato solo per la parte comune con lo scatto (stessa pipeline `addPhoto`).
- Nessuna eliminazione definitiva: l'archiviazione è il soft delete previsto per l'MVP.
