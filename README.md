# Object Finder — Android MVP

App Android nativa per registrare oggetti fisici con posizione GPS e ritrovarli su mappa.
Kotlin, Jetpack Compose, Room (offline-first), Supabase Auth + PostgREST, WorkManager,
Google Maps / OpenStreetMap tramite provider intercambiabili.

## Requisiti
- Android Studio Ladybug o successivo (oppure solo Android SDK da riga di comando)
- JDK 17 o 21 per Gradle (Gradle 8.9 non supporta JDK 25)
- Android SDK Platform 35 + Build-Tools 35
- Progetto Supabase (facoltativo: senza, l'app funziona come archivio locale)
- (Opzionale) Google Maps API key; senza, la mappa usa OpenStreetMap

## Configurazione
1. Copiare `android/local.properties.example` in `android/local.properties` e impostare `sdk.dir`.
   Facoltativo: aggiungere `GOOGLE_MAPS_API_KEY=...` nello stesso file.
2. Copiare `android/app/src/main/assets/config.properties.example` in `config.properties`
   e impostare `SUPABASE_URL` e `SUPABASE_ANON_KEY` (anon/publishable key).
3. In Supabase, SQL Editor, eseguire in ordine:
   `backend/migrations/001_initial_schema.sql`, `002_storage_policies.sql`, `003_sync_support.sql`,
   `004_project_members_rpc.sql`, `005_object_photos.sql`.
   La 005 è necessaria per le foto: corregge le policy Storage della 002, che negavano sempre
   lettura e upload.
4. In Supabase → Authentication → Providers abilitare Email/Password. Con "Confirm email" attivo,
   l'app mostra il messaggio di conferma e chiede di accedere dopo aver confermato.

## Build e test
```bash
cd android
./gradlew :app:testDebugUnitTest   # test unitari (merge sync, cursore, filtri, sessione)
./gradlew :app:lintDebug
./gradlew :app:assembleDebug       # APK in app/build/outputs/apk/debug/
```
Se il JDK di default è il 25: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew ...`.

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
- Onboarding con login/registrazione Supabase (email + password) o uso offline senza account
- Sessione persistente con refresh automatico del token; richieste REST autenticate (JWT utente)
- Bootstrap automatico del progetto cloud al primo accesso; gli oggetti creati offline vengono
  riassegnati al progetto e sincronizzati
- Progetti multipli: creazione, cambio progetto attivo, ruolo dell'utente per progetto
- Membri: elenco, aggiunta per email (account esistente), cambio ruolo, rimozione, uscita dal progetto;
  permessi in app allineati alle policy RLS (viewer in sola lettura, gestione membri a owner/admin)
- Elenco oggetti con ricerca e filtri (in uso / archiviati / tutti), stato di sync per oggetto
- Creazione e modifica oggetto: nome, categoria, stato, note, posizione GPS
- Foto degli oggetti: scatto o galleria, ridimensionate (max 1920 px, JPEG) senza metadati EXIF,
  salvate in locale e caricate nel bucket privato `object-photos`; miniatura in elenco,
  visualizzatore a schermo intero, eliminazione sincronizzata, download delle foto degli altri membri
- Etichette QR: ogni oggetto ha un QR (`objectfinder://object/<id>`, solo l'identificativo) da
  condividere o stampare; scansione dall'app (scanner Google Play services, senza permesso
  fotocamera) o con la fotocamera di sistema tramite deep link; se l'oggetto è in un altro progetto
  l'app propone di passarci
- Archiviazione/ripristino (soft delete sincronizzato)
- Permessi posizione con spiegazione, gestione del rifiuto permanente e scorciatoia alle impostazioni
- Acquisizione GPS ad alta precisione con fallback sull'ultima posizione nota
- Sincronizzazione bidirezionale: push delle modifiche pending + pull incrementale con cursore,
  sync immediata dopo ogni modifica (appena c'è rete) e periodica ogni ora
- Mappa Google Maps / OpenStreetMap con preferenza salvata, fallback automatico su OSM senza API key,
  apertura dell'oggetto dal marker

## Note di sicurezza
- Non inserire mai la `service_role` key nell'app: solo anon/publishable key con RLS.
- I token di sessione sono in SharedPreferences private; backup cloud e trasferimento dispositivo
  sono disabilitati (`allowBackup=false`, `data_extraction_rules.xml`).

## Cartografia
- Google Maps: richiede API key e billing abilitato nel progetto Google Cloud.
- OpenStreetMap: il provider OSM usa tile pubblici solo per sviluppo/test. Per produzione configurare
  un tile provider conforme ai termini d'uso, oppure un servizio commerciale/self-hosted.
- `MapEngine` consente di aggiungere MapLibre come provider OSM avanzato in una milestone successiva.

## Stato e prossimi step
Vedi `docs/NEXT_STEPS.md`. In breve: inviti a utenti non ancora registrati, upload foto su Storage, QR/NFC,
tracker BLE/GPS, matching immagini.
