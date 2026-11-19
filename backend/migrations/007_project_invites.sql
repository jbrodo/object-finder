-- Object Finder / Migration 007 — inviti a progetto per email non ancora registrate.
-- Eseguire dopo 001–006.
--
-- Flusso: owner/admin invita un'email → se l'account esiste viene aggiunto subito, altrimenti si
-- crea un invito pendente. Quando la persona si registra e accede, l'app chiama accept_my_invites()
-- che converte gli inviti in membership.
-- SICUREZZA: l'invito è accettato solo se l'email dell'account è confermata. Lasciare attivo
-- "Confirm email" in Supabase Auth: con l'auto-conferma chiunque potrebbe registrarsi con
-- l'email invitata e ottenere l'accesso al progetto.

create table if not exists public.project_invites (
    id uuid primary key default gen_random_uuid(),
    project_id uuid not null references public.projects(id) on delete cascade,
    email text not null check (email = lower(btrim(email)) and email like '%_@_%'),
    role public.project_role not null default 'editor' check (role <> 'owner'),
    invited_by uuid references auth.users(id) on delete set null default auth.uid(),
    created_at timestamptz not null default now(),
    accepted_at timestamptz,
    accepted_by uuid references auth.users(id) on delete set null,
    unique (project_id, email)
);

create index if not exists project_invites_email_idx on public.project_invites(email) where accepted_at is null;

alter table public.project_invites enable row level security;

-- Lettura e revoca per owner/admin del progetto. Nessuna insert/update diretta: solo via RPC.
create policy invites_select on public.project_invites for select
using (public.has_project_role(project_id, array['owner','admin']::public.project_role[]));
create policy invites_delete on public.project_invites for delete
using (public.has_project_role(project_id, array['owner','admin']::public.project_role[]));

grant select, delete on public.project_invites to authenticated;

/**
 * Aggiunge un membro se l'account esiste, altrimenti crea/aggiorna un invito pendente.
 * Restituisce 'added' oppure 'invited'.
 */
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

    insert into public.project_invites(project_id, email, role, invited_by)
    values (p_project, v_email, p_role, auth.uid())
    on conflict (project_id, email) do update
        set role = excluded.role, invited_by = excluded.invited_by,
            created_at = now(), accepted_at = null, accepted_by = null;
    return 'invited';
end;
$$;

/** Accetta gli inviti pendenti per l'email (confermata) dell'utente corrente. Restituisce quanti. */
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
        where email = v_email and accepted_at is null
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
