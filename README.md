# Object Finder — Android MVP

Base incrementale Android nativa: Kotlin, Jetpack Compose, Room, Supabase REST/PostgREST,
Google Maps / OpenStreetMap predisposti tramite provider separati.

## Requisiti
- Android Studio Ladybug o successivo
- JDK 17
- Android SDK 35
- Progetto Supabase
- (Opzionale) Google Maps API key per la mappa Google

## Avvio
1. Aprire la cartella `android/` in Android Studio.
2. Copiare `android/local.properties.example` in `android/local.properties` e impostare `sdk.dir`.
3. Copiare `android/app/src/main/assets/config.properties.example` in `config.properties`.
4. Impostare `SUPABASE_URL`, `SUPABASE_ANON_KEY`, `GOOGLE_MAPS_API_KEY`.
5. Eseguire la migration `backend/migrations/001_initial_schema.sql` nel SQL Editor di Supabase.
6. Sincronizzare Gradle e avviare su un dispositivo Android con GPS.

## Build APK con GitHub Actions
La workflow `Build APK` compila l'APK debug a ogni push e pull request; è possibile avviarla anche manualmente dalla scheda Actions.
Al termine, scaricare l'artifact `object-finder-debug-apk` dalla run. La secret repository `GOOGLE_MAPS_API_KEY` è opzionale: senza, l'APK viene comunque compilato ma Google Maps non è configurato.

## Funzioni MVP incluse nel codice
- Dashboard minimale
- Creazione e lista oggetti
- Acquisizione posizione GPS
- Persistenza locale Room
- Repository con sincronizzazione Supabase REST/PostgREST
- Worker WorkManager per sincronizzazione
- Modello di progetto e oggetto
- Schermata mappa con selettore Google Maps / OpenStreetMap e marker degli oggetti geolocalizzati

## Nota sullo stato
Questo è uno scaffold MVP sorgente. Non è stato compilato in questo ambiente e richiede configurazione delle credenziali, verifica delle versioni SDK e test su dispositivo.
La sincronizzazione Supabase richiede ancora la milestone Auth: il client attuale usa la anon/publishable key ma non dispone di sessione utente, quindi le policy RLS rifiutano correttamente le scritture. Inoltre il progetto demo `default-project` va sostituito con un UUID di progetto creato per l'utente autenticato.
Non inserire mai la `service_role` key nell'app: usare esclusivamente la anon/publishable key con RLS.

## Cartografia
- Google Maps: richiede API key e billing abilitato nel progetto Google Cloud.
- OpenStreetMap: il provider OSM usa tile pubblici solo per sviluppo/test. Per produzione configurare un tile provider conforme ai termini d'uso, oppure un servizio commerciale/self-hosted.
- L'interfaccia `MapProvider` consente di aggiungere MapLibre come provider OSM avanzato in una milestone successiva.

## Prossimi step
1. Completare onboarding/auth Supabase e gestione sessione.
2. Implementare upload immagini Supabase Storage.
3. Implementare selezione Google Maps/OSM nelle impostazioni.
4. Implementare QR/NFC.
5. Implementare tracker BLE/GPS esterni e telemetria.
6. Implementare matching immagini AI e ARCore.
