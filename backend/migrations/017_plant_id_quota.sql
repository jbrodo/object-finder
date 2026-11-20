-- Object Finder / Migration 017 — quota del riconoscimento piante online (Pl@ntNet).
-- La chiave Pl@ntNet sta solo nella Edge Function `identify-plant`; il piano gratuito vale 500
-- identificazioni al giorno per tutta l'app. Qui si contano, per utente e in totale, così un
-- utente non consuma la quota di tutti. Solo la funzione (service role) legge e scrive.

create table if not exists public.plant_id_usage (
    day date not null,
    user_id uuid not null references auth.users(id) on delete cascade,
    count integer not null default 0 check (count >= 0),
    primary key (day, user_id)
);

alter table public.plant_id_usage enable row level security;
-- Nessuna policy: client (anon/authenticated) senza accesso.
revoke all on public.plant_id_usage from anon, authenticated;

/**
 * Prende una richiesta dalla quota di oggi (UTC). Restituisce quante ne restano all'utente dopo
 * questa, oppure -1 se il limite dell'utente o quello globale è già raggiunto (nulla consumato).
 */
create or replace function public.plant_quota_take(p_user uuid, p_user_limit integer, p_global_limit integer)
returns integer
language plpgsql
security definer
set search_path = public
as $$
declare
    v_today date := (now() at time zone 'utc')::date;
    v_user integer;
    v_total integer;
begin
    -- Una richiesta alla volta: il controllo e l'incremento non devono incrociarsi.
    perform pg_advisory_xact_lock(hashtext('plant_quota_take'));
    delete from plant_id_usage where day < v_today - 30;
    select coalesce(sum(count), 0) into v_total from plant_id_usage where day = v_today;
    select coalesce(max(count), 0) into v_user from plant_id_usage where day = v_today and user_id = p_user;
    if v_user >= p_user_limit or v_total >= p_global_limit then
        return -1;
    end if;
    insert into plant_id_usage(day, user_id, count) values (v_today, p_user, 1)
    on conflict (day, user_id) do update set count = plant_id_usage.count + 1;
    return p_user_limit - v_user - 1;
end;
$$;

revoke all on function public.plant_quota_take(uuid, integer, integer) from public, anon, authenticated;
do $$
begin
    if exists (select 1 from pg_roles where rolname = 'service_role') then
        grant execute on function public.plant_quota_take(uuid, integer, integer) to service_role;
        grant select, insert, update, delete on public.plant_id_usage to service_role;
    end if;
end;
$$;
