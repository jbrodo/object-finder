# Object Finder — Android MVP

App Android nativa per registrare oggetti fisici con posizione GPS e ritrovarli su mappa.
Kotlin, Jetpack Compose, Room (offline-first), Supabase Auth + PostgREST, WorkManager,
Google Maps / OpenStreetMap tramite provider intercambiabili.

## Requisiti
- Android Studio con supporto AGP 9 (oppure solo Android SDK da riga di comando)
- JDK 17 o successivo
- Android SDK Platform 37.1 (`platforms/android-37.1`, richiesta da Compose 1.13) + Build-Tools 37;
  se manca, Gradle la scarica da solo (licenze SDK accettate)
- Progetto Supabase (facoltativo: senza, l'app funziona come archivio locale)
- (Opzionale) Google Maps API key; senza, la mappa usa OpenStreetMap

## Configurazione
1. Copiare `android/local.properties.example` in `android/local.properties` e impostare `sdk.dir`.
   Facoltativo: aggiungere `GOOGLE_MAPS_API_KEY=...` nello stesso file.
2. Copiare `android/app/src/main/assets/config.properties.example` in `config.properties`
   e impostare `SUPABASE_URL` e `SUPABASE_ANON_KEY` (anon/publishable key).
3. Schema del database: automatico con la workflow *Database migrations* (vedi sotto), oppure a mano
   da SQL Editor eseguendo in ordine:
   `backend/migrations/001_initial_schema.sql`, `002_storage_policies.sql`, `003_sync_support.sql`,
   `004_project_members_rpc.sql`, `005_object_photos.sql`, `006_object_identifiers.sql`,
   `007_project_invites.sql`, `008_location_history.sql`, `009_object_deletions.sql`.
   La 005 è necessaria per le foto: corregge le policy Storage della 002, che negavano sempre
   lettura e upload.
4. In Supabase → Authentication → Providers abilitare Email/Password e **lasciare attivo
   "Confirm email"**: gli inviti a progetto vengono accettati solo per email confermate; con
   l'auto-conferma chiunque potrebbe registrarsi con l'email invitata.

### Link dell'email di conferma (Supabase Auth)
Alla registrazione l'app chiede a Supabase di far tornare il link di conferma nell'app
(`objectfinder://auth/callback`, flusso PKCE): aprendolo dal telefono si entra già autenticati.
Lo stesso ritorno serve a "Password dimenticata?": dal link si rientra nell'app e si sceglie subito la
nuova password (da Impostazioni c'è anche "Cambia password").
Supabase accetta solo indirizzi autorizzati, altrimenti usa il **Site URL** del progetto (di default
`http://localhost:3000`, da cui il link a localhost). Una volta sola, nel pannello Supabase:

- Authentication → URL Configuration → **Redirect URLs** → aggiungi `objectfinder://auth/callback`
- **Site URL**: un indirizzo che non sia localhost (usato quando il link viene aperto altrove, ad
  esempio dal PC). L'email risulta confermata comunque; poi si accede con email e password.

## Migration automatiche (GitHub Actions)
La workflow `Database migrations` parte quando cambia un file in `backend/migrations/`:
- **pull request**: applica tutte le migration su un Postgres+PostGIS temporaneo (con uno stub di
  `auth`/`storage`), verifica che una seconda esecuzione non applichi nulla; non tocca il DB reale;
- **push su `main`**: dopo la validazione applica al database Supabase **solo le migration pendenti**.

Regole (`backend/scripts/migrate.sh`, usabile anche a mano con `DATABASE_URL=... backend/scripts/migrate.sh`):
- registro in `ops.schema_migrations` (schema non esposto dalle API);
- ogni file gira in una transazione insieme alla sua registrazione: se fallisce non resta nulla a metà;
- una migration già applicata non si modifica (checksum): per cambiare qualcosa si crea `008_...sql`;
- niente `begin`/`commit` dentro i file e niente comandi non transazionali (es. `create index concurrently`).

Configurazione:
1. Secret `SUPABASE_DB_URL`: in Supabase → **Connect** → **Session pooler** (i runner GitHub non
   raggiungono la connessione diretta, solo IPv6). Formato
   `postgresql://postgres.<ref>:<password>@aws-0-<region>.pooler.supabase.com:5432/postgres`.
   ```bash
   gh secret set SUPABASE_DB_URL   # incollare la stringa quando richiesto (non resta nella cronologia)
   ```
2. **Migration già eseguite a mano** dal SQL Editor: lanciare una volta la workflow da Actions →
   *Database migrations* → *Run workflow* con `baseline` = ultima versione applicata (es. `007`) e
   `dry_run` disattivato. Le segna come applicate senza rieseguirle. Senza baseline la workflow si
   ferma con un messaggio invece di provare a ricreare lo schema.
3. Consigliato: in Settings → Environments → `production` aggiungere un *required reviewer*, così
   ogni applicazione al database attende un'approvazione.

## Build e test
```bash
cd android
./gradlew :app:testDebugUnitTest   # test unitari (merge sync, cursore, filtri, sessione)
./gradlew :app:lintDebug
./gradlew :app:assembleDebug       # APK in app/build/outputs/apk/debug/
```
Toolchain: Gradle 9.9, Android Gradle Plugin 9.5 (Kotlin integrato), Kotlin 2.4, KSP 2.3,
Compose BOM 2026.11, Room 2.8, Ktor 3.6; compileSdk 37.1, targetSdk 36, minSdk 26.
Le versioni che devono restare allineate sono raggruppate in `.github/dependabot.yml`.

## Build APK con GitHub Actions
La workflow `Build APK` esegue test unitari, lint e compila l'APK debug a ogni push e pull request;
è possibile avviarla anche manualmente dalla scheda Actions.
Al termine, scaricare l'artifact `object-finder-debug-apk` dalla run. La secret repository
`GOOGLE_MAPS_API_KEY` è opzionale: senza, l'APK viene comunque compilato e usa OpenStreetMap.

## Release firmate (GitHub Releases)
La workflow `Release APK` parte a ogni tag `vX.Y.Z` (o manualmente da Actions): esegue i test,
compila l'APK release firmato, verifica la firma e lo pubblica come GitHub Release
(`object-finder-vX.Y.Z.apk` + checksum SHA-256), scaricabile direttamente da chi ha accesso al repo.
```bash
git tag v0.3.0 && git push origin v0.3.0
```
Secret richiesti: `RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`,
`RELEASE_KEY_PASSWORD` (senza, la workflow fallisce invece di pubblicare un APK firmato debug).
In locale la firma si attiva con le stesse chiavi come variabili d'ambiente o in `local.properties`
(`RELEASE_KEYSTORE_PATH=...`); senza, `assembleRelease` usa la chiave debug.

**Il keystore release non va perso**: senza la stessa chiave non si possono pubblicare aggiornamenti
installabili sopra le versioni esistenti. Conservarne una copia sicura fuori dal repo.
L'APK debug della CI e quello release hanno firme diverse: per passare dall'uno all'altro va
disinstallata l'app.

## Funzioni incluse
- Onboarding con login/registrazione Supabase (email + password) o uso offline senza account;
  integrazione con il gestore password di Android (autofill: in registrazione il campo è "nuova
  password", così il gestore può proporne una forte; salvataggio dopo l'accesso e proposta delle
  credenziali salvate all'apertura), pulsante per mostrare la password, indicatore di robustezza
- Sessione persistente con refresh automatico del token; richieste REST autenticate (JWT utente)
- Bootstrap automatico del progetto cloud al primo accesso; gli oggetti creati offline vengono
  riassegnati al progetto e sincronizzati
- Progetti multipli: creazione, cambio progetto attivo, ruolo dell'utente per progetto
- Membri: elenco, aggiunta per email, cambio ruolo, rimozione, uscita dal progetto; chi non ha
  ancora un account riceve un invito che si attiva alla registrazione: email automatica con il link
  all'app (Edge Function `send-invite`, vedi "Email di invito") oppure messaggio da condividere;
  permessi in app allineati alle policy RLS (viewer in sola lettura, gestione membri a owner/admin)
- Elenco oggetti con ricerca e filtri (in uso / archiviati / tutti), stato di sync per oggetto
- Scheda oggetto in consultazione: "Dove si trova" (descrizione libera, distanza, da quando) con
  "Portami lì" e "L'ho spostato qui"; modifica come azione esplicita
- Creazione e modifica oggetto: nome, "Dove", posizione GPS, categoria, stato, note
- Foto degli oggetti: scatto o galleria, ridimensionate (max 1920 px, JPEG) senza metadati EXIF,
  salvate in locale e caricate nel bucket privato `object-photos`; miniatura in elenco,
  visualizzatore a schermo intero, eliminazione sincronizzata; le foto degli altri membri si scaricano
  su richiesta (in sync solo le copertine, le altre all'apertura della scheda) con una cache da 150 MB
- Etichette QR: ogni oggetto ha un QR (`objectfinder://object/<id>`, solo l'identificativo) da
  condividere o stampare; scansione dall'app (scanner Google Play services, senza permesso
  fotocamera) o con la fotocamera di sistema tramite deep link; se l'oggetto è in un altro progetto
  l'app propone di passarci
- Etichette NFC: dal menu della scheda si scrive su un tag NTAG lo stesso link del QR (più l'Android
  Application Record); avvicinando il telefono al tag si apre la scheda, anche ad app chiusa
- Codici esistenti collegati agli oggetti (barcode del prodotto, numero di serie, QR di terzi):
  scansionati o inseriti a mano; scansionandoli si apre l'oggetto (scelta se più oggetti hanno lo
  stesso codice); la ricerca in elenco trova anche per codice
- Archiviazione/ripristino (soft delete sincronizzato)
- Permessi posizione con spiegazione, gestione del rifiuto permanente e scorciatoia alle impostazioni
- Acquisizione GPS ad alta precisione con fallback sull'ultima posizione nota
- Conflitti tra membri: concorrenza ottimistica sulla `version` del server; se un altro membro ha
  modificato (o eliminato) l'oggetto nel frattempo, nessuna versione viene persa e la scheda mostra
  le differenze campo per campo con la scelta "Tieni la mia" / "Usa quella del server"
- Eliminazione definitiva (owner/admin) con foto, codici e storico, propagata agli altri dispositivi
- Sincronizzazione bidirezionale: push delle modifiche pending + pull incrementale con cursore
  composto `(updated_at, id)` e finestra di ricontrollo; al logout le copie già sincronizzate
  vengono tolte dal telefono,
  sync immediata dopo ogni modifica (appena c'è rete) e periodica ogni ora
- Export dell'inventario (icona nella barra della lista oggetti): CSV per Excel/fogli di calcolo (separatore `;`,
  UTF-8, formule neutralizzate) o PDF A4 stampabile con foto, codici e QR di ogni oggetto;
  condivisione o salvataggio sul telefono
- "Vicini": elenco ordinato per distanza dal telefono, con la distanza su ogni oggetto
- Storico posizioni: ogni nuova posizione (oltre l'incertezza del GPS) viene registrata e sincronizzata
  (tabella `locations`, append-only); in scheda: data, coordinate, spostamento, apertura su mappa
- "Portami lì": navigazione a piedi verso l'oggetto con Google Maps (o altra app mappe)
- Mappa Google Maps / OpenStreetMap con preferenza salvata, fallback automatico su OSM senza API key;
  segnaposti con il nome raggruppati in base allo zoom (toccando un gruppo la mappa si avvicina; gli
  oggetti nello stesso punto si aprono a ventaglio), cerchio di incertezza del GPS, posizioni
  approssimate tratteggiate, riquadro con apertura e "Portami lì"
- Riconoscimento visivo ("Riconosci oggetto" nella lista): una foto e l'app propone gli oggetti del
  progetto più simili, tutto sul telefono (MediaPipe Image Embedder, modello scaricato dalla build con
  SHA-256 fissato); a parità di aspetto vince l'oggetto più vicino; se nessuno corrisponde si
  registra come nuovo con quella foto. Le impronte delle foto restano locali e non si sincronizzano
- Suggerimenti dalla foto (Cactus Mirage, Apache-2.0, sul telefono): registrando un oggetto l'app
  propone fino a 3 nomi e legge scritte e numeri di serie; un tocco li usa (nome, note o codice
  collegato). Motore 4.1.4 collegato via JNI (`src/main/cpp`, NDK r28), solo ARM; il modello (22 MB)
  si scarica una volta su richiesta, con revisione e SHA-256 fissati
- Posizione corretta a mano: mirino fisso sulla mappa ("Correggi il punto sulla mappa"), salvata
  come posizione indicata a mano; rilevamento GPS che tiene il fix migliore dei primi secondi

## Distribuzione ai tester (Firebase App Distribution)
Il repository è privato: chi non ha accesso a GitHub non può scaricare l'APK dalle release. A ogni
tag `vX.Y.Z` il workflow **Release APK** carica l'APK firmato anche su Firebase App Distribution, che
avvisa i tester (app "Firebase App Tester") e permette l'aggiornamento con un tocco. Configurazione,
una volta sola:

1. console.firebase.google.com → crea un progetto → aggiungi un'app Android con package
   `it.objectfinder.app` (il file `google-services.json` non serve).
2. App Distribution → Tester e gruppi → crea il gruppo `testers` e aggiungi le email.
3. Google Cloud console del progetto → IAM → Account di servizio → nuovo account con ruolo
   **Firebase App Distribution Admin** → Chiavi → crea chiave JSON.
4. Secret del repository:

| Secret | Valore |
|---|---|
| `FIREBASE_APP_ID` | Impostazioni progetto → App Android → ID app (`1:1234…:android:abcd…`) |
| `FIREBASE_SERVICE_ACCOUNT` | contenuto del file JSON della chiave |

Gruppo diverso da `testers`: variabile del repository `FIREBASE_TESTER_GROUPS`. Senza i secret il
passo viene saltato e la release su GitHub resta invariata.

## Email di invito
La Edge Function `backend/supabase/functions/send-invite` manda l'email a chi viene invitato senza
account. La pubblica il workflow **Edge functions** a ogni modifica della funzione su `main`
(environment `production`). Servono questi secret del repository:

| Secret | Dove si trova |
|---|---|
| `SUPABASE_ACCESS_TOKEN` | supabase.com → Account → Access Tokens (token personale) |
| `RESEND_API_KEY` | resend.com → API Keys (piano gratuito: 3.000 email/mese) |
| `INVITE_FROM` | mittente su un dominio verificato in Resend, es. `Object Finder <inviti@tuodominio.it>` |

`SUPABASE_URL` (già presente per l'APK) serve a ricavare il project ref. Dopo aver impostato i secret
si avvia il workflow da Actions → Edge functions → Run workflow. Finché la funzione non è pubblicata,
l'app propone la condivisione manuale dell'invito. Limiti anti-spam: un reinvio ogni 10 minuti per
invito, 50 email al giorno per progetto (migration 011); il destinatario è sempre quello salvato
nell'invito, mai un indirizzo scelto dal client.

## Note di sicurezza
- Non inserire mai la `service_role` key nell'app: solo anon/publishable key con RLS.
- I token di sessione sono cifrati con AES-256-GCM (chiave nell'Android Keystore, non estraibile) in
  SharedPreferences private; backup cloud e trasferimento dispositivo sono disabilitati.

## Cartografia
- Google Maps: richiede API key e billing abilitato nel progetto Google Cloud.
- OpenStreetMap: il provider OSM usa tile pubblici solo per sviluppo/test. Per produzione configurare
  un tile provider conforme ai termini d'uso, oppure un servizio commerciale/self-hosted.
- `MapEngine` consente di aggiungere MapLibre come provider OSM avanzato in una milestone successiva.

## Stato e prossimi step
Vedi `docs/NEXT_STEPS.md`. In breve: fotocamera in-app (CameraX), riconoscimento visivo degli oggetti,
distribuzione su Google Play/Firebase, tracker BLE/GPS e geofence.
