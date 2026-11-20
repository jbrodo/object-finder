-- Test SQL degli inviti (eseguito in CI dopo le migration, su un database usa e getta).
-- Ogni controllo fallisce con un'eccezione esplicita; nessun dato resta (transazione annullata).
begin;

insert into auth.users(id, email, email_confirmed_at) values
    ('00000000-0000-0000-0000-0000000000a1', 'owner@example.com', now()),
    ('00000000-0000-0000-0000-0000000000b2', 'nuovo@example.com', now()),
    ('00000000-0000-0000-0000-0000000000c3', 'scaduto@example.com', now());
insert into public.projects(id, name, owner_id)
values ('00000000-0000-0000-0000-0000000000f1', 'Casa', '00000000-0000-0000-0000-0000000000a1');

-- Il proprietario invita due indirizzi senza account (gli account "esistono" solo dopo).
delete from auth.users where email in ('nuovo@example.com', 'scaduto@example.com');
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-0000000000a1', true);
do $$
begin
    if public.invite_project_member('00000000-0000-0000-0000-0000000000f1', 'Nuovo@Example.com', 'editor') <> 'invited' then
        raise exception 'atteso invited';
    end if;
    perform public.invite_project_member('00000000-0000-0000-0000-0000000000f1', 'scaduto@example.com', 'viewer');
    if (select expires_at from public.project_invites where email = 'nuovo@example.com')
        not between now() + interval '29 days' and now() + interval '31 days' then
        raise exception 'scadenza attesa a 30 giorni';
    end if;
end;
$$;

-- Un invito scade; ripeterlo lo rinnova.
update public.project_invites set expires_at = now() - interval '1 day' where email = 'scaduto@example.com';
do $$
begin
    perform public.invite_project_member('00000000-0000-0000-0000-0000000000f1', 'scaduto@example.com', 'viewer');
    if (select expires_at from public.project_invites where email = 'scaduto@example.com') < now() + interval '29 days' then
        raise exception 'ripetere l''invito deve rinnovarlo';
    end if;
end;
$$;
update public.project_invites set expires_at = now() - interval '1 day' where email = 'scaduto@example.com';

-- Registrazione: l'invito valido viene accettato, quello scaduto no.
insert into auth.users(id, email, email_confirmed_at) values
    ('00000000-0000-0000-0000-0000000000b2', 'nuovo@example.com', now()),
    ('00000000-0000-0000-0000-0000000000c3', 'scaduto@example.com', now());
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-0000000000b2', true);
do $$
begin
    if public.accept_my_invites() <> 1 then raise exception 'invito valido non accettato'; end if;
end;
$$;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-0000000000c3', true);
do $$
begin
    if public.accept_my_invites() <> 0 then raise exception 'invito scaduto accettato'; end if;
    if exists (select 1 from public.project_members where user_id = '00000000-0000-0000-0000-0000000000c3') then
        raise exception 'membro aggiunto da invito scaduto';
    end if;
end;
$$;

select 'invites_test: ok' as result;
rollback;
