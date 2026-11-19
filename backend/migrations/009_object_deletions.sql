-- Object Finder / Migration 009 — propagazione delle eliminazioni definitive.
-- Eseguire dopo 001–008.
--
-- Il pull incrementale vede solo le righe modificate: un oggetto eliminato resterebbe per sempre
-- sugli altri dispositivi. Un trigger registra ogni eliminazione in deleted_objects; i client la
-- leggono con lo stesso cursore temporale e rimuovono la copia locale.

create table if not exists public.deleted_objects (
    object_id  uuid primary key,
    project_id uuid not null references public.projects(id) on delete cascade,
    deleted_at timestamptz not null default now(),
    deleted_by uuid references auth.users(id) on delete set null default auth.uid()
);

create index if not exists deleted_objects_project_time_idx
    on public.deleted_objects(project_id, deleted_at);

alter table public.deleted_objects enable row level security;

-- Solo lettura per i membri del progetto; scritta esclusivamente dal trigger.
create policy deleted_objects_select on public.deleted_objects for select
using (public.is_project_member(project_id));

grant select on public.deleted_objects to authenticated;

create or replace function public.record_object_deletion()
returns trigger
language plpgsql security definer
set search_path = public
as $$
begin
    insert into public.deleted_objects(object_id, project_id, deleted_at, deleted_by)
    values (old.id, old.project_id, now(), auth.uid())
    on conflict (object_id) do update
        set deleted_at = excluded.deleted_at, deleted_by = excluded.deleted_by;
    return old;
end;
$$;

drop trigger if exists objects_record_deletion on public.objects;
create trigger objects_record_deletion
after delete on public.objects
for each row execute function public.record_object_deletion();

-- Un oggetto ricreato con lo stesso id (raro: ripristino) non deve risultare eliminato.
create or replace function public.clear_object_deletion()
returns trigger
language plpgsql security definer
set search_path = public
as $$
begin
    delete from public.deleted_objects where object_id = new.id;
    return new;
end;
$$;

drop trigger if exists objects_clear_deletion on public.objects;
create trigger objects_clear_deletion
after insert on public.objects
for each row execute function public.clear_object_deletion();
