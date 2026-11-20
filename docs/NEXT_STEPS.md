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
- [x] Toolchain aggiornata (AGP 9.5, Gradle 9.9, Kotlin 2.4, KSP 2.3, Compose 1.13, Room 2.8, Ktor 3.6)
- [x] targetSdk 37 (Android 17): verificato su emulatore API 37 (permessi posizione, GPS, tastiera, Indietro, selettore mappa, fotocamera)

## M2 — Cloud identity and projects
- [x] Supabase Auth login/signup (email + password, gestione conferma email)
- [x] Default project bootstrap after signup
- [x] Authenticated REST requests (JWT, refresh automatico, retry su 401)
- [x] Sync pull cursor (incrementale su `updated_at`) e regola di conflitto base
- [x] Create project flow / selezione tra più progetti
- [x] Membership flow: aggiunta per email, ruoli, rimozione, uscita (RPC migration 004)
- [x] Inviti a email non ancora registrate (migration 007, accettazione al login con email confermata)
- [x] Email di invito lato server (Edge Function `send-invite` + Resend, limiti anti-spam, migration 011)
- [x] Conflitti multi-utente: concorrenza ottimistica su `version`, scelta guidata dall'utente
- [x] Cifratura token con Android Keystore (AES-256-GCM)
- [x] Link di conferma email e "Password dimenticata?" con ritorno nell'app (PKCE), "Cambia password"
- [x] Eliminazione definitiva propagata (migration 009, tombstone `deleted_objects`)
- [x] Pull con cursore composto `(updated_at, id)` e finestra di ricontrollo di 2 minuti
- [x] Logout: le copie locali già sincronizzate vengono tolte dal telefono (restano le modifiche non inviate)
- [x] Foto su richiesta: in sync solo le copertine, le altre all'apertura della scheda; cache LRU da 150 MB

## M3 — Identification
- [x] Foto oggetto: scatto (app fotocamera di sistema) e galleria, normalizzazione JPEG
- [x] Fotocamera in-app CameraX (anteprima Compose, cornice guida, scatti in serie, flash, tocco per mettere a fuoco; ripiego sulla fotocamera di sistema)
- [x] QR: etichetta per oggetto (condividi/stampa), scansione in-app, deep link `objectfinder://object/<id>`
- [x] Collegare codici esistenti (barcode prodotto, seriale) tramite `object_identifiers` (migration 006)
- [x] Etichette NFC: scrittura del link dell'oggetto (+ AAR) su tag NTAG, apertura della scheda avvicinando il telefono
- [x] Private Storage upload (bucket `object-photos`, path `<project>/<object>/<file>`, migration 005)
- [x] Selezione multipla nella lista e foglio A4 di etichette QR (3 × 7)
- [x] Riconoscimento visivo on-device (MediaPipe Image Embedder, MobileNetV3 large): impronte locali delle foto, classifica per somiglianza + vicinanza, "Registra come nuovo"
- [x] Impronte condivise sul server (migration 016): un telefono nuovo o un altro membro le scarica invece di riscaricare le foto
- [x] Riconoscimento piante: Pl@ntNet online tramite Edge Function `identify-plant` (quota, migration 017) + modello PlantCLEF offline (ONNX, 50 MB su richiesta)
- [ ] Attivare Pl@ntNet online: chiave my.plantnet.org nel secret `PLANTNET_API_KEY` (+ `SUPABASE_ACCESS_TOKEN` per pubblicare le funzioni)
- [ ] Piante: provare su telefono reale (velocità a 512 px, qualità su foto di casa/orto/rose); nomi comuni italiani anche offline (es. GBIF, licenza da verificare)
- [ ] Taratura delle soglie di somiglianza sulle foto reali (negli APK debug si vede la percentuale)
- [x] Suggerimenti dalla foto con Cactus Mirage (nome, testo letto → note o numero di serie), JNI su ARM
- [ ] Verificare Mirage su telefono ARM reale (velocità, qualità dei nomi in italiano); emulatore x86 non supportato

## M3.5 — Ritrovare gli oggetti
- [x] "Vicini": ordinamento per distanza dal telefono
- [x] Storico posizioni (migration 008, append-only) con spostamenti
- [x] "Portami lì" (navigazione verso l'oggetto)
- [x] Scheda in consultazione con "Dove si trova", campo "Dove" (migration 010), segnaposti raggruppati sulla mappa
- [x] Correzione manuale della posizione con mirino sulla mappa (migration 012, `location_source`)
- [x] Precisione GPS visibile (approssimata oltre ±25 m) e rilevamento del fix migliore entro 15 s
- [x] Mappa: raggruppamento in base allo zoom, zoom sul gruppo, ventaglio per oggetti nello stesso punto
- [x] Password: autofill/Credential Manager, mostra password, robustezza
- [x] Prestiti (migration 014): "prestato a … fino al …", promemoria alle 9 e subito se già scaduto, filtro "In prestito"
- [x] Luoghi strutturati (migration 015): albero per progetto (Casa › Garage › Scaffale), selettore con creazione/rinomina/eliminazione, filtro per luogo con i sotto-luoghi, percorso in etichette ed export

## Distribuzione e qualità
- [x] Export inventario CSV / PDF
- [x] Firebase App Distribution nella pipeline di release (attiva con i secret `FIREBASE_*`)
- [ ] Test interno su Google Play

## Backlog (prioritizzato)
- [ ] Scorciatoie dell'app (Riconosci, Scansiona, Nuovo oggetto) e widget di ricerca
- [ ] Taratura delle soglie del riconoscimento sulle foto reali (percentuali dalle prove con la v1.7.1)
- [ ] Segnalazione crash (Firebase Crashlytics, stesso progetto di App Distribution)
- [ ] Spostare oggetti in un altro progetto (anche in blocco): serve una migrazione che sposti le foto su Storage (`<project>/<object>/…`) e verifichi i permessi su entrambi i progetti
- [ ] R8 in release con regole verificate (Ktor, serialization, Room, MediaPipe, JNI di Mirage) e Baseline Profiles per avvio e scorrimento (prerequisiti della skill jetpack-compose-performance)

## M4 — Tracking
- [ ] BLE scan/connect
- [ ] GPS tracker ingestion endpoint
- [ ] Device credentials and telemetry validation
- [ ] Location retention policies
- [ ] Geofence evaluation and notifications

## Limiti noti
- La finestra di ricontrollo del pull (2 minuti) copre le transazioni lente; una scrittura che resta
  aperta più a lungo sul server potrebbe ancora sfuggire fino alla sincronizzazione completa successiva.
- Le modifiche non inviate di un account restano sul telefono dopo il logout e partono solo quando si
  rientra con lo stesso account.
- `invite_project_member` rivela a owner/admin se un'email ha già un account ("aggiunto" vs
  "invitato"): enumerazione limitata ai gestori di progetto.
- Gli inviti non hanno scadenza; si revocano manualmente dalla schermata Membri.
- Verifiche end-to-end contro il progetto Supabase reale (account di test, emulatore): login,
  sync con cursore composto, foto su richiesta, logout, inviti; non coperti ruoli multipli e conflitti
  tra due account reali.
- Le foto non ancora scaricate richiedono la rete all'apertura della scheda; offline compare l'icona
  "non ancora scaricata".
- Le etichette NFC sono verificate con intent simulati e test unitari; la scrittura reale va provata su
  un telefono con NFC e tag NTAG213/215/216.
- Il file picker su emulatore API 30 non mostra file copiati via adb: l'import da galleria è stato
  verificato solo per la parte comune con lo scatto (stessa pipeline `addPhoto`).
- Lo scanner QR in-app richiede Google Play services recente (modulo scaricato on-demand); sul
  vecchio emulatore API 30 non è disponibile e l'app lo segnala. Verificati deep link (a freddo e a
  caldo), generazione/decodifica del QR e risoluzione; la lettura reale va provata su dispositivo.
- Se un oggetto eliminato da un altro membro viene "ricreato" da chi aveva modifiche in sospeso,
  le foto già cancellate sul server non tornano (solo i dati dell'oggetto e le foto non ancora inviate).
