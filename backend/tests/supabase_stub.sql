-- Stub minimo dell'ambiente Supabase, SOLO per validare le migration su un Postgres+PostGIS
-- vuoto (CI). Non eseguire su un progetto Supabase reale: lì ruoli e schemi esistono già.
do $$
begin
    if not exists (select 1 from pg_roles where rolname = 'anon') then create role anon nologin; end if;
    if not exists (select 1 from pg_roles where rolname = 'authenticated') then create role authenticated nologin; end if;
end;
$$;

create schema if not exists auth;
create schema if not exists storage;
create schema if not exists extensions;
grant usage on schema extensions to anon, authenticated;

create table if not exists auth.users (
    id uuid primary key,
    email text,
    email_confirmed_at timestamptz
);
create or replace function auth.uid() returns uuid language sql stable as
$$ select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid $$;
grant usage on schema auth to authenticated;
grant execute on function auth.uid() to authenticated;

create table if not exists storage.buckets (id text primary key, name text, public boolean);
create table if not exists storage.objects (
    id uuid primary key default gen_random_uuid(),
    bucket_id text,
    name text
);
create or replace function storage.foldername(name text) returns text[] language sql as
$$ select string_to_array(name, '/') $$;
alter table storage.objects enable row level security;
grant usage on schema storage to authenticated;
grant select, insert, delete on storage.objects to authenticated;

-- Come su Supabase: le estensioni stanno nello schema `extensions`, incluso nel search_path.
do $$
begin
    execute format('alter database %I set search_path = "$user", public, extensions', current_database());
end;
$$;
