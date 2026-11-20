-- Test SQL delle impronte condivise (migration 016): permessi per ruolo, formato, cancellazione con la foto.
begin;
insert into auth.users(id, email, email_confirmed_at) values
    ('00000000-0000-0000-0000-0000000000a1', 'owner@example.com', now()),
    ('00000000-0000-0000-0000-0000000000d4', 'viewer@example.com', now()),
    ('00000000-0000-0000-0000-0000000000e5', 'estraneo@example.com', now());
insert into public.projects(id, name, owner_id)
values ('00000000-0000-0000-0000-0000000000f1', 'Casa', '00000000-0000-0000-0000-0000000000a1');
insert into public.project_members(project_id, user_id, role) values
    ('00000000-0000-0000-0000-0000000000f1', '00000000-0000-0000-0000-0000000000a1', 'owner'),
    ('00000000-0000-0000-0000-0000000000f1', '00000000-0000-0000-0000-0000000000d4', 'viewer')
on conflict do nothing;
insert into public.objects(id, project_id, name)
values ('00000000-0000-0000-0000-0000000000b1', '00000000-0000-0000-0000-0000000000f1', 'Trapano');
insert into public.object_photos(id, object_id, storage_path)
values ('00000000-0000-0000-0000-0000000000c1', '00000000-0000-0000-0000-0000000000b1', '00000000-0000-0000-0000-0000000000f1/00000000-0000-0000-0000-0000000000b1/a.jpg');

set local role authenticated;
-- Il proprietario salva un'impronta (2 float = 8 byte).
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-0000000000a1', true);
insert into public.photo_embeddings(photo_id, model, vector)
values ('00000000-0000-0000-0000-0000000000c1', 'm@1', encode('\x0000803f00000000'::bytea, 'base64'));
do $$
begin
    begin
        insert into public.photo_embeddings(photo_id, model, vector)
        values ('00000000-0000-0000-0000-0000000000c1', 'm@2', encode('\x000080'::bytea, 'base64'));
        raise exception 'vettore di lunghezza non multipla di 4 accettato';
    exception when check_violation then null;
    end;
end;
$$;

-- Il viewer legge ma non scrive.
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-0000000000d4', true);
do $$
begin
    if (select count(*) from public.photo_embeddings) <> 1 then raise exception 'il viewer non vede l''impronta'; end if;
    begin
        insert into public.photo_embeddings(photo_id, model, vector)
        values ('00000000-0000-0000-0000-0000000000c1', 'm@3', encode('\x0000803f'::bytea, 'base64'));
        raise exception 'il viewer ha scritto un''impronta';
    exception when insufficient_privilege then null;
    end;
end;
$$;

-- Chi non è nel progetto non vede nulla.
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-0000000000e5', true);
do $$
begin
    if (select count(*) from public.photo_embeddings) <> 0 then raise exception 'impronta visibile a un estraneo'; end if;
end;
$$;

-- Cancellando la foto sparisce anche l'impronta.
reset role;
delete from public.object_photos where id = '00000000-0000-0000-0000-0000000000c1';
do $$
begin
    if exists (select 1 from public.photo_embeddings) then raise exception 'impronta rimasta senza foto'; end if;
end;
$$;
select 'embeddings_test: ok' as result;
rollback;
