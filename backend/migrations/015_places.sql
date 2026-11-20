-- Object Finder / Migration 015 — luoghi strutturati (Casa → Garage → Scaffale).
-- Un albero di luoghi per progetto; ogni oggetto può stare in un luogo (place_id) e il campo
-- libero "place" resta come dettaglio ("ripiano alto"). Cancellare un luogo non cancella nulla:
-- i sotto-luoghi salgono di livello (parent null) e gli oggetti restano senza luogo.

create table if not exists public.places (
    id uuid primary key default gen_random_uuid(),
    project_id uuid not null references public.projects(id) on delete cascade,
    parent_id uuid references public.places(id) on delete set null,
    name text not null check (char_length(btrim(name)) between 1 and 80),
    created_by uuid references auth.users(id) on delete set null default auth.uid(),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create index if not exists places_project_idx on public.places(project_id);
create index if not exists places_parent_idx on public.places(parent_id);

alter table public.objects
    add column if not exists place_id uuid references public.places(id) on delete set null;
create index if not exists objects_place_idx on public.objects(place_id) where place_id is not null;

/** Coerenza dell'albero: stesso progetto del genitore, niente cicli, profondità limitata. */
create or replace function public.check_place_tree()
returns trigger language plpgsql as $$
declare
    v_parent uuid := new.parent_id;
    v_project uuid;
    v_depth integer := 0;
begin
    new.updated_at = now();
    while v_parent is not null loop
        if v_parent = new.id then
            raise exception 'a place cannot contain itself' using errcode = '22023';
        end if;
        select parent_id, project_id into v_parent, v_project from public.places where id = v_parent;
        if v_project is distinct from new.project_id then
            raise exception 'parent place belongs to another project' using errcode = '22023';
        end if;
        v_depth := v_depth + 1;
        if v_depth > 8 then
            raise exception 'places can be nested at most 8 levels' using errcode = '22023';
        end if;
    end loop;
    return new;
end;
$$;

drop trigger if exists places_tree_check on public.places;
create trigger places_tree_check
    before insert or update on public.places
    for each row execute function public.check_place_tree();

/** Un oggetto può stare solo in un luogo del proprio progetto. */
create or replace function public.check_object_place()
returns trigger language plpgsql as $$
begin
    if new.place_id is not null and not exists (
        select 1 from public.places p where p.id = new.place_id and p.project_id = new.project_id
    ) then
        raise exception 'place belongs to another project' using errcode = '22023';
    end if;
    return new;
end;
$$;

drop trigger if exists objects_place_check on public.objects;
create trigger objects_place_check
    before insert or update of place_id, project_id on public.objects
    for each row execute function public.check_object_place();

alter table public.places enable row level security;

drop policy if exists places_select on public.places;
create policy places_select on public.places for select
using (public.is_project_member(project_id));

drop policy if exists places_insert on public.places;
create policy places_insert on public.places for insert
with check (public.has_project_role(project_id, array['owner','admin','editor']::public.project_role[]));

drop policy if exists places_update on public.places;
create policy places_update on public.places for update
using (public.has_project_role(project_id, array['owner','admin','editor']::public.project_role[]))
with check (public.has_project_role(project_id, array['owner','admin','editor']::public.project_role[]));

drop policy if exists places_delete on public.places;
create policy places_delete on public.places for delete
using (public.has_project_role(project_id, array['owner','admin','editor']::public.project_role[]));

grant select, insert, update, delete on public.places to authenticated;
