-- Object Finder / Migration 013 — scadenza degli inviti.
-- Un invito vale 30 giorni; reinviarlo (dall'app o con una nuova email) lo rinnova. Gli inviti
-- scaduti restano visibili ai gestori come "scaduti" finché non li revocano o li rinnovano.

alter table public.project_invites
    add column if not exists expires_at timestamptz not null default (now() + interval '30 days');

-- Inviti già esistenti: 30 giorni da oggi (non da quando erano stati creati), per non farli
-- scadere di colpo con l'aggiornamento.
update public.project_invites set expires_at = now() + interval '30 days'
where accepted_at is null and expires_at < now() + interval '30 days' - interval '1 minute';

/** Come 007, con il rinnovo della scadenza quando l'invito viene ripetuto. */
create or replace function public.invite_project_member(
    p_project uuid,
    p_email text,
    p_role public.project_role default 'editor'
)
returns text
language plpgsql volatile security definer
set search_path = public
as $$
declare
    v_email text := lower(btrim(p_email));
    v_user uuid;
begin
    if not public.has_project_role(p_project, array['owner','admin']::public.project_role[]) then
        raise exception 'only owners and admins can add members' using errcode = '42501';
    end if;
    if p_role = 'owner' then
        raise exception 'the owner role cannot be assigned' using errcode = '22023';
    end if;
    if v_email !~ '^[^@\s]+@[^@\s]+\.[^@\s]+$' then
        raise exception 'invalid email address' using errcode = '22023';
    end if;

    select id into v_user from auth.users where lower(email) = v_email limit 1;
    if v_user is not null then
        perform public.add_project_member(p_project, v_email, p_role);
        return 'added';
    end if;

    insert into public.project_invites(project_id, email, role, invited_by, expires_at)
    values (p_project, v_email, p_role, auth.uid(), now() + interval '30 days')
    on conflict (project_id, email) do update
        set role = excluded.role, invited_by = excluded.invited_by,
            created_at = now(), accepted_at = null, accepted_by = null,
            expires_at = now() + interval '30 days';
    return 'invited';
end;
$$;

/** Come 007, ma solo inviti non scaduti. */
create or replace function public.accept_my_invites()
returns integer
language plpgsql volatile security definer
set search_path = public
as $$
declare
    v_uid uuid := auth.uid();
    v_email text;
    v_count integer := 0;
    v_invite record;
begin
    if v_uid is null then
        raise exception 'not authenticated' using errcode = '42501';
    end if;
    select lower(email) into v_email from auth.users
    where id = v_uid and email_confirmed_at is not null;
    if v_email is null then
        return 0; -- email non confermata: nessun invito accettato
    end if;

    for v_invite in
        select id, project_id, role from public.project_invites
        where email = v_email and accepted_at is null and expires_at > now()
        for update
    loop
        insert into public.project_members(project_id, user_id, role)
        values (v_invite.project_id, v_uid, v_invite.role)
        on conflict (project_id, user_id) do nothing;
        update public.project_invites
            set accepted_at = now(), accepted_by = v_uid
            where id = v_invite.id;
        v_count := v_count + 1;
    end loop;
    return v_count;
end;
$$;

revoke all on function public.invite_project_member(uuid, text, public.project_role) from public, anon;
revoke all on function public.accept_my_invites() from public, anon;
grant execute on function public.invite_project_member(uuid, text, public.project_role) to authenticated;
grant execute on function public.accept_my_invites() to authenticated;
