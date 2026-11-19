-- Object Finder / Migration 008 — storico posizioni degli oggetti (tabella locations della 001).
-- Eseguire dopo 001–007.

-- 001 definiva locations_access "for all" con USING = qualsiasi membro: anche un viewer poteva
-- modificare o cancellare lo storico. Lo storico è append-only per i client: lettura ai membri,
-- inserimento a editor e tracker, nessun update/delete (la pulizia sarà una retention lato server).
drop policy if exists locations_access on public.locations;

create policy locations_select on public.locations for select
using (exists (
    select 1 from public.objects o
    where o.id = object_id and public.is_project_member(o.project_id)
));

create policy locations_insert on public.locations for insert
with check (exists (
    select 1 from public.objects o
    where o.id = object_id
      and public.has_project_role(o.project_id, array['owner','admin','editor','tracker']::public.project_role[])
));

-- Coerenza dei dati inviati dai client.
alter table public.locations drop constraint if exists locations_source_valid;
alter table public.locations add constraint locations_source_valid
    check (source in ('phone', 'gps', 'ble', 'uwb', 'manual', 'imported'));
alter table public.locations drop constraint if exists locations_accuracy_valid;
alter table public.locations add constraint locations_accuracy_valid
    check (accuracy_meters is null or accuracy_meters >= 0);
-- Un timestamp nel futuro (orologio del telefono sbagliato) altererebbe l'ordine dello storico.
alter table public.locations drop constraint if exists locations_recorded_not_future;
alter table public.locations add constraint locations_recorded_not_future
    check (recorded_at <= received_at + interval '1 day');
