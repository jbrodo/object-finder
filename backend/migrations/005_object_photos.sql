-- Object Finder / Migration 005 — foto degli oggetti.
-- Eseguire dopo 001–004.

-- 001 definiva photos_access "for all" con USING = qualsiasi membro: anche un viewer poteva
-- cancellare righe. Si separano lettura (membri) e scrittura/cancellazione (editor+).
drop policy if exists photos_access on public.object_photos;

create policy photos_select on public.object_photos for select
using (exists (
    select 1 from public.objects o
    where o.id = object_id and public.is_project_member(o.project_id)
));

create policy photos_insert on public.object_photos for insert
with check (exists (
    select 1 from public.objects o
    where o.id = object_id
      and public.has_project_role(o.project_id, array['owner','admin','editor']::public.project_role[])
));

create policy photos_delete on public.object_photos for delete
using (exists (
    select 1 from public.objects o
    where o.id = object_id
      and public.has_project_role(o.project_id, array['owner','admin','editor']::public.project_role[])
));

-- Le foto sono immutabili: nessuna policy di update.

-- L'autore è sempre il chiamante.
alter table public.object_photos alter column created_by set default auth.uid();

create or replace function public.enforce_photo_author()
returns trigger language plpgsql as $$
begin
    new.created_by = auth.uid();
    return new;
end;
$$;

drop trigger if exists object_photos_enforce_author on public.object_photos;
create trigger object_photos_enforce_author
before insert on public.object_photos
for each row execute function public.enforce_photo_author();

-- storage_path deve appartenere all'oggetto: <project_id>/<object_id>/<file>.
alter table public.object_photos drop constraint if exists object_photos_path_format;
alter table public.object_photos add constraint object_photos_path_format
    check (storage_path ~ '^[0-9a-f-]{36}/[0-9a-f-]{36}/[^/]+$');

create index if not exists object_photos_object_idx on public.object_photos(object_id);

-- Storage. Le policy della 002 avevano un bug di scoping: nella subquery su public.objects la
-- colonna non qualificata `name` veniva risolta su objects.name (nome dell'oggetto) invece che
-- sul path del file, quindi lettura e upload venivano sempre negati. Si sostituiscono con una
-- funzione che riceve il path come argomento.
create or replace function public.photo_path_allowed(p_path text, allowed public.project_role[])
returns boolean
language sql stable security definer
set search_path = public
as $$
    select exists (
        select 1 from public.objects o
        where o.id::text = split_part(p_path, '/', 2)
          and o.project_id::text = split_part(p_path, '/', 1)
          and (case when allowed is null then public.is_project_member(o.project_id)
                    else public.has_project_role(o.project_id, allowed) end)
    );
$$;
revoke all on function public.photo_path_allowed(text, public.project_role[]) from public, anon;
grant execute on function public.photo_path_allowed(text, public.project_role[]) to authenticated;

drop policy if exists "project members can read object photos" on storage.objects;
drop policy if exists "editors can upload object photos" on storage.objects;
drop policy if exists "editors can delete object photos" on storage.objects;

create policy "project members can read object photos"
on storage.objects for select to authenticated
using (bucket_id = 'object-photos' and public.photo_path_allowed(name, null));

create policy "editors can upload object photos"
on storage.objects for insert to authenticated
with check (
    bucket_id = 'object-photos'
    and public.photo_path_allowed(name, array['owner','admin','editor']::public.project_role[])
);

create policy "editors can delete object photos"
on storage.objects for delete to authenticated
using (
    bucket_id = 'object-photos'
    and public.photo_path_allowed(name, array['owner','admin','editor']::public.project_role[])
);
