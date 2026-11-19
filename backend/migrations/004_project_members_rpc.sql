-- Object Finder / Migration 004 — gestione membri di progetto via RPC.
-- Il client non può leggere auth.users: le funzioni SECURITY DEFINER espongono solo
-- l'email dei membri dei progetti di cui il chiamante fa parte, e verificano i ruoli.
-- MVP: si può aggiungere solo chi ha già un account (nessun invito via email pendente).

create or replace function public.list_project_members(p_project uuid)
returns table (user_id uuid, email text, role public.project_role, created_at timestamptz)
language plpgsql stable security definer
set search_path = public
as $$
begin
    if not public.is_project_member(p_project) then
        raise exception 'not a project member' using errcode = '42501';
    end if;
    return query
        select pm.user_id, u.email::text, pm.role, pm.created_at
        from public.project_members pm
        join auth.users u on u.id = pm.user_id
        where pm.project_id = p_project
        order by (pm.role = 'owner') desc, pm.created_at asc;
end;
$$;

create or replace function public.add_project_member(
    p_project uuid,
    p_email text,
    p_role public.project_role default 'editor'
)
returns uuid
language plpgsql volatile security definer
set search_path = public
as $$
declare
    v_user uuid;
begin
    if not public.has_project_role(p_project, array['owner','admin']::public.project_role[]) then
        raise exception 'only owners and admins can add members' using errcode = '42501';
    end if;
    if p_role = 'owner' then
        raise exception 'the owner role cannot be assigned' using errcode = '22023';
    end if;
    select id into v_user from auth.users where lower(email) = lower(trim(p_email)) limit 1;
    if v_user is null then
        raise exception 'no account found for this email' using errcode = 'P0002';
    end if;
    if exists (select 1 from public.project_members
               where project_id = p_project and user_id = v_user and role = 'owner') then
        raise exception 'the owner role cannot be changed' using errcode = '22023';
    end if;
    insert into public.project_members(project_id, user_id, role)
    values (p_project, v_user, p_role)
    on conflict (project_id, user_id) do update set role = excluded.role;
    return v_user;
end;
$$;

create or replace function public.remove_project_member(p_project uuid, p_user uuid)
returns void
language plpgsql volatile security definer
set search_path = public
as $$
begin
    -- Un membro può sempre uscire da solo; owner/admin possono rimuovere altri.
    if p_user <> auth.uid()
       and not public.has_project_role(p_project, array['owner','admin']::public.project_role[]) then
        raise exception 'only owners and admins can remove members' using errcode = '42501';
    end if;
    if exists (select 1 from public.project_members
               where project_id = p_project and user_id = p_user and role = 'owner') then
        raise exception 'the project owner cannot be removed' using errcode = '22023';
    end if;
    delete from public.project_members where project_id = p_project and user_id = p_user;
end;
$$;

revoke all on function public.list_project_members(uuid) from public, anon;
revoke all on function public.add_project_member(uuid, text, public.project_role) from public, anon;
revoke all on function public.remove_project_member(uuid, uuid) from public, anon;
grant execute on function public.list_project_members(uuid) to authenticated;
grant execute on function public.add_project_member(uuid, text, public.project_role) to authenticated;
grant execute on function public.remove_project_member(uuid, uuid) to authenticated;
