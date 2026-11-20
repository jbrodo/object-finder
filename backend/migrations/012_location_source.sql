-- Object Finder / Migration 012 — origine della posizione dell'oggetto.
-- 'gps': rilevata dal telefono (accuracy_meters = incertezza); 'manual': indicata a mano sulla
-- mappa (punto scelto dall'utente, accuracy_meters null). Default 'gps' per i dati esistenti e
-- per le app ≤ 1.3, che non conoscono la colonna.

alter table public.objects
    add column if not exists location_source text not null default 'gps';

alter table public.objects drop constraint if exists objects_location_source_valid;
alter table public.objects add constraint objects_location_source_valid
    check (location_source in ('gps', 'manual'));
