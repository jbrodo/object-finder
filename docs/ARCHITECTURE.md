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
Room is the local source of truth for the Android UI. Mutations are stored locally first,
then synchronized by WorkManager. For concurrent multi-user edits, add server versions,
mutation IDs and conflict resolution in the next milestone.

## Security
Never ship Supabase service_role credentials in the mobile app.
Use authenticated Supabase sessions and RLS. Tracker ingestion should move to a trusted
Edge Function/API with per-device credentials; do not expose tracker write access to anon.
