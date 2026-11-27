# Architecture decisions

## Domain identity
Every physical asset has a stable UUID. Location sources (phone, GPS tracker, BLE gateway)
are observations, not identities.

## Location model
- `objects.latitude/longitude/location`: current known position for quick map rendering.
- `locations`: append-only position history.
- `tracking_devices`: external tracker identity and assignment.
- `source`: phone, gps, ble, uwb, manual, imported.

## Cartography
The app domain uses coordinates and `MapMarker`; UI providers are replaceable.
Google Maps is suitable for Google basemap and Places ecosystem.
OpenStreetMap data is accessed through a provider implementation. Public OSM tile servers
are capacity-limited and must not be treated as a free production CDN.

## Offline-first
Room is the local source of truth for the Android UI. Mutations are stored locally first
(`syncStatus = pending`), then synchronized by WorkManager (`SyncScheduler`).

Sync (`ObjectRepository.sync`):
1. `ProjectRepository.ensureActiveProject()` — lists the user's projects, creates a default one
   if none exists, and reassigns objects created offline (`default-project`) to it.
2. Sync places first, then push pending objects if the user can edit. New objects are inserted;
   existing objects use an optimistic update against their server `version`. A mismatch is
   reconciled when possible or recorded as a conflict for the user to resolve. An object is marked
   synced only if it was not edited locally while the request was in flight.
3. Pull objects in pages of 500 using a composite `(updated_at, id)` cursor and a two-minute
   lookback. Pending local edits are preserved; open conflicts retain the local copy and update
   the server copy shown to the user. Deletion tombstones remove synced local copies or open a
   conflict when local changes are pending.
4. Sync identifiers, location history and photos after objects. Viewers pull without pushing
   changes. Photos are downloaded on demand apart from covers.

Project creation uses a client-generated UUID and `Prefer: return=minimal`: the projects SELECT
policy depends on the owner membership created by an AFTER INSERT trigger, which is not visible
to RETURNING (verified against the migrations: `return=representation` fails RLS).

The lookback reduces the risk of missing late commits, but transactions lasting longer than the
window can still be missed by incremental pulls; see `NEXT_STEPS.md`.

## Android layers
- `data/local` — Room entity/DAO/database.
- `data/remote` — Ktor clients for GoTrue (`SupabaseAuthApi`) and PostgREST (`SupabaseRestApi`),
  typed `SupabaseException`s; 401 triggers one token refresh and retry.
- `data` — repositories (`AuthRepository`, `ProjectRepository`, `ObjectRepository`), `SessionStore`,
  `SettingsStore`, pure merge rules in `SyncMerge` (unit tested).
- `ui` — Compose screens + ViewModels; `AppContainer` is a single manual DI container owned by
  `ObjectFinderApplication` (one Room instance, one HTTP client per process).

## Security
Never ship Supabase service_role credentials in the mobile app.
Use authenticated Supabase sessions and RLS. Tracker ingestion should move to a trusted
Edge Function/API with per-device credentials; do not expose tracker write access to anon.
