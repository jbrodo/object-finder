-- Object Finder / Migration 006 — codici esistenti collegati agli oggetti (barcode, seriali, QR esterni).
-- Eseguire dopo 001–005.

-- 001: unique(identifier_type, identifier_value) era globale su tutti i progetti. Un barcode EAN
-- identifica il modello di prodotto, non il singolo pezzo: due oggetti uguali (anche di utenti
-- diversi) devono poter avere lo stesso codice, e il conflitto rivelava l'esistenza del codice in
-- progetti altrui. L'unicità diventa per oggetto.
alter table public.object_identifiers
    drop constraint if exists object_identifiers_identifier_type_identifier_value_key;
alter table public.object_identifiers
    add constraint object_identifiers_object_type_value_key unique (object_id, identifier_type, identifier_value);

-- Tipo `barcode` (EAN/UPC/Code128…) distinto dal QR.
alter table public.object_identifiers drop constraint if exists object_identifiers_identifier_type_check;
alter table public.object_identifiers add constraint object_identifiers_identifier_type_check
    check (identifier_type in ('qr', 'nfc', 'serial', 'barcode', 'external'));

alter table public.object_identifiers drop constraint if exists object_identifiers_value_length;
alter table public.object_identifiers add constraint object_identifiers_value_length
    check (char_length(identifier_value) between 1 and 512);

create index if not exists object_identifiers_object_idx on public.object_identifiers(object_id);
create index if not exists object_identifiers_value_idx on public.object_identifiers(identifier_value);

-- 001 definiva identifiers_access "for all" con USING = qualsiasi membro: un viewer poteva cancellare.
drop policy if exists identifiers_access on public.object_identifiers;

create policy identifiers_select on public.object_identifiers for select
using (exists (
    select 1 from public.objects o
    where o.id = object_id and public.is_project_member(o.project_id)
));

create policy identifiers_insert on public.object_identifiers for insert
with check (exists (
    select 1 from public.objects o
    where o.id = object_id
      and public.has_project_role(o.project_id, array['owner','admin','editor']::public.project_role[])
));

create policy identifiers_delete on public.object_identifiers for delete
using (exists (
    select 1 from public.objects o
    where o.id = object_id
      and public.has_project_role(o.project_id, array['owner','admin','editor']::public.project_role[])
));
-- Nessun update: un codice si rimuove e si ricrea.
