-- Object Finder / Migration 003 — supporto al client Android (auth + sync).
-- Eseguire dopo 001 e 002.

-- L'autore viene valorizzato dal server: il client non deve (e non può in modo affidabile) inviarlo.
alter table public.objects alter column created_by set default auth.uid();
alter table public.projects alter column owner_id set default auth.uid();

-- Pull incrementale: WHERE project_id = ? AND updated_at > ? ORDER BY updated_at.
create index if not exists objects_project_updated_idx
    on public.objects(project_id, updated_at);

-- created_by non deve cambiare con gli upsert successivi né essere falsificato all'insert.
create or replace function public.enforce_object_author()
returns trigger language plpgsql as $$
begin
    if tg_op = 'INSERT' then
        new.created_by = auth.uid();
    else
        new.created_by = old.created_by;
    end if;
    return new;
end;
$$;

drop trigger if exists objects_enforce_author on public.objects;
create trigger objects_enforce_author
before insert or update on public.objects
for each row execute function public.enforce_object_author();
