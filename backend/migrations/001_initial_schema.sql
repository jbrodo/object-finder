-- Object Finder / Migration 001
-- Eseguire nel SQL Editor Supabase.
-- Estensioni geospaziali e UUID.
create extension if not exists pgcrypto;
create extension if not exists postgis with schema extensions;

create type public.project_role as enum ('owner', 'admin', 'editor', 'viewer', 'tracker');
create type public.object_status as enum ('active', 'inactive', 'maintenance', 'archived');
create type public.tracking_mode as enum ('static', 'phone', 'gps', 'ble', 'uwb', 'manual');

create table public.projects (
    id uuid primary key default gen_random_uuid(),
    name text not null,
    description text not null default '',
    owner_id uuid not null references auth.users(id) on delete restrict,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table public.project_members (
    project_id uuid not null references public.projects(id) on delete cascade,
    user_id uuid not null references auth.users(id) on delete cascade,
    role public.project_role not null default 'viewer',
    created_at timestamptz not null default now(),
    primary key (project_id, user_id)
);

create table public.objects (
    id uuid primary key default gen_random_uuid(),
    project_id uuid not null references public.projects(id) on delete cascade,
    name text not null,
    category text not null default 'general',
    notes text not null default '',
    status public.object_status not null default 'active',
    tracking_mode public.tracking_mode not null default 'static',
    latitude double precision,
    longitude double precision,
    accuracy_meters real,
    location geography(point, 4326),
    last_seen_at timestamptz,
    version integer not null default 1,
    created_by uuid references auth.users(id) on delete set null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint objects_latitude_valid check (latitude is null or latitude between -90 and 90),
    constraint objects_longitude_valid check (longitude is null or longitude between -180 and 180),
    constraint objects_coordinates_pair check ((latitude is null) = (longitude is null))
);

create index objects_project_idx on public.objects(project_id);
create index objects_location_gix on public.objects using gist(location);
create index objects_updated_idx on public.objects(updated_at desc);

create table public.object_photos (
    id uuid primary key default gen_random_uuid(),
    object_id uuid not null references public.objects(id) on delete cascade,
    storage_path text not null,
    photo_type text not null default 'reference',
    captured_at timestamptz,
    created_by uuid references auth.users(id) on delete set null,
    created_at timestamptz not null default now()
);

create table public.object_identifiers (
    id uuid primary key default gen_random_uuid(),
    object_id uuid not null references public.objects(id) on delete cascade,
    identifier_type text not null check (identifier_type in ('qr', 'nfc', 'serial', 'external')),
    identifier_value text not null,
    created_at timestamptz not null default now(),
    unique(identifier_type, identifier_value)
);

create table public.tracking_devices (
    id uuid primary key default gen_random_uuid(),
    object_id uuid not null references public.objects(id) on delete cascade,
    device_type public.tracking_mode not null,
    external_id text not null unique,
    status text not null default 'active',
    battery_percent smallint,
    last_contact_at timestamptz,
    created_at timestamptz not null default now(),
    constraint battery_range check (battery_percent is null or battery_percent between 0 and 100)
);

create table public.locations (
    id uuid primary key default gen_random_uuid(),
    object_id uuid not null references public.objects(id) on delete cascade,
    device_id uuid references public.tracking_devices(id) on delete set null,
    source text not null default 'phone',
    latitude double precision not null check (latitude between -90 and 90),
    longitude double precision not null check (longitude between -180 and 180),
    accuracy_meters real,
    speed_mps real,
    heading_degrees real,
    battery_percent smallint,
    recorded_at timestamptz not null,
    received_at timestamptz not null default now(),
    location geography(point, 4326) generated always as
        (extensions.st_setsrid(extensions.st_makepoint(longitude, latitude), 4326)::extensions.geography) stored
);

create index locations_object_time_idx on public.locations(object_id, recorded_at desc);
create index locations_location_gix on public.locations using gist(location);

create table public.points_of_interest (
    id uuid primary key default gen_random_uuid(),
    project_id uuid not null references public.projects(id) on delete cascade,
    name text not null,
    description text not null default '',
    category text not null default 'general',
    latitude double precision not null check (latitude between -90 and 90),
    longitude double precision not null check (longitude between -180 and 180),
    location geography(point, 4326) generated always as
        (extensions.st_setsrid(extensions.st_makepoint(longitude, latitude), 4326)::extensions.geography) stored,
    created_by uuid references auth.users(id) on delete set null,
    created_at timestamptz not null default now()
);

create index poi_project_idx on public.points_of_interest(project_id);
create index poi_location_gix on public.points_of_interest using gist(location);

create table public.object_events (
    id uuid primary key default gen_random_uuid(),
    object_id uuid not null references public.objects(id) on delete cascade,
    user_id uuid references auth.users(id) on delete set null,
    event_type text not null,
    payload jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create table public.object_embeddings (
    id uuid primary key default gen_random_uuid(),
    object_id uuid not null references public.objects(id) on delete cascade,
    model_name text not null,
    embedding_ref text not null,
    created_at timestamptz not null default now()
);

create table public.geofences (
    id uuid primary key default gen_random_uuid(),
    project_id uuid not null references public.projects(id) on delete cascade,
    name text not null,
    center geography(point, 4326) not null,
    radius_meters double precision not null check (radius_meters > 0),
    enabled boolean not null default true,
    created_at timestamptz not null default now()
);

create table public.notifications (
    id uuid primary key default gen_random_uuid(),
    project_id uuid not null references public.projects(id) on delete cascade,
    user_id uuid not null references auth.users(id) on delete cascade,
    event_type text not null,
    payload jsonb not null default '{}'::jsonb,
    read_at timestamptz,
    created_at timestamptz not null default now()
);

create table public.audit_logs (
    id uuid primary key default gen_random_uuid(),
    project_id uuid references public.projects(id) on delete set null,
    actor_id uuid references auth.users(id) on delete set null,
    action text not null,
    entity_type text not null,
    entity_id uuid,
    details jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

-- updated_at + geometria derivata per objects
create or replace function public.set_object_metadata()
returns trigger language plpgsql as $$
begin
    new.updated_at = now();
    new.version = old.version + 1;
    if new.latitude is not null and new.longitude is not null then
        new.location = extensions.st_setsrid(
            extensions.st_makepoint(new.longitude, new.latitude), 4326
        )::extensions.geography;
    else
        new.location = null;
    end if;
    return new;
end;
$$;

create trigger objects_metadata_before_update
before update on public.objects
for each row execute function public.set_object_metadata();

create or replace function public.set_object_location_on_insert()
returns trigger language plpgsql as $$
begin
    if new.latitude is not null and new.longitude is not null then
        new.location = extensions.st_setsrid(
            extensions.st_makepoint(new.longitude, new.latitude), 4326
        )::extensions.geography;
    end if;
    return new;
end;
$$;

create trigger objects_location_before_insert
before insert on public.objects
for each row execute function public.set_object_location_on_insert();

-- Membership/authorization helpers. SECURITY DEFINER avoids recursive RLS lookup.
create or replace function public.has_project_role(p_project uuid, allowed public.project_role[])
returns boolean
language sql stable security definer
set search_path = public
as $$
    select exists (
        select 1 from public.project_members pm
        where pm.project_id = p_project
          and pm.user_id = auth.uid()
          and pm.role = any(allowed)
    );
$$;

create or replace function public.is_project_member(p_project uuid)
returns boolean
language sql stable security definer
set search_path = public
as $$
    select exists (
        select 1 from public.project_members pm
        where pm.project_id = p_project and pm.user_id = auth.uid()
    );
$$;

-- Create owner membership automatically when project is created.
create or replace function public.add_project_owner()
returns trigger language plpgsql security definer
set search_path = public
as $$
begin
    insert into public.project_members(project_id, user_id, role)
    values (new.id, new.owner_id, 'owner');
    return new;
end;
$$;

create trigger projects_add_owner
after insert on public.projects
for each row execute function public.add_project_owner();

-- RLS
alter table public.projects enable row level security;
alter table public.project_members enable row level security;
alter table public.objects enable row level security;
alter table public.object_photos enable row level security;
alter table public.object_identifiers enable row level security;
alter table public.tracking_devices enable row level security;
alter table public.locations enable row level security;
alter table public.points_of_interest enable row level security;
alter table public.object_events enable row level security;
alter table public.object_embeddings enable row level security;
alter table public.geofences enable row level security;
alter table public.notifications enable row level security;
alter table public.audit_logs enable row level security;

create policy projects_select on public.projects for select
using (public.is_project_member(id));
create policy projects_insert on public.projects for insert
with check (owner_id = auth.uid());
create policy projects_update on public.projects for update
using (public.has_project_role(id, array['owner','admin']::public.project_role[]))
with check (public.has_project_role(id, array['owner','admin']::public.project_role[]));
create policy projects_delete on public.projects for delete
using (public.has_project_role(id, array['owner']::public.project_role[]));

create policy members_select on public.project_members for select
using (public.is_project_member(project_id));
create policy members_manage on public.project_members for all
using (public.has_project_role(project_id, array['owner','admin']::public.project_role[]))
with check (public.has_project_role(project_id, array['owner','admin']::public.project_role[]));

create policy objects_select on public.objects for select
using (public.is_project_member(project_id));
create policy objects_insert on public.objects for insert
with check (public.has_project_role(project_id, array['owner','admin','editor']::public.project_role[]));
create policy objects_update on public.objects for update
using (public.has_project_role(project_id, array['owner','admin','editor']::public.project_role[]))
with check (public.has_project_role(project_id, array['owner','admin','editor']::public.project_role[]));
create policy objects_delete on public.objects for delete
using (public.has_project_role(project_id, array['owner','admin']::public.project_role[]));

-- Related rows are authorized through their parent object/project.
create policy photos_access on public.object_photos for all
using (exists(select 1 from public.objects o where o.id = object_id and public.is_project_member(o.project_id)))
with check (exists(select 1 from public.objects o where o.id = object_id and public.has_project_role(o.project_id, array['owner','admin','editor']::public.project_role[])));

create policy identifiers_access on public.object_identifiers for all
using (exists(select 1 from public.objects o where o.id = object_id and public.is_project_member(o.project_id)))
with check (exists(select 1 from public.objects o where o.id = object_id and public.has_project_role(o.project_id, array['owner','admin','editor']::public.project_role[])));

create policy tracking_devices_access on public.tracking_devices for all
using (exists(select 1 from public.objects o where o.id = object_id and public.is_project_member(o.project_id)))
with check (exists(select 1 from public.objects o where o.id = object_id and public.has_project_role(o.project_id, array['owner','admin','editor']::public.project_role[])));

create policy locations_access on public.locations for all
using (exists(select 1 from public.objects o where o.id = object_id and public.is_project_member(o.project_id)))
with check (exists(select 1 from public.objects o where o.id = object_id and public.has_project_role(o.project_id, array['owner','admin','editor','tracker']::public.project_role[])));

create policy poi_access on public.points_of_interest for all
using (public.is_project_member(project_id))
with check (public.has_project_role(project_id, array['owner','admin','editor']::public.project_role[]));

create policy events_access on public.object_events for all
using (exists(select 1 from public.objects o where o.id = object_id and public.is_project_member(o.project_id)))
with check (exists(select 1 from public.objects o where o.id = object_id and public.has_project_role(o.project_id, array['owner','admin','editor']::public.project_role[])));

create policy embeddings_access on public.object_embeddings for all
using (exists(select 1 from public.objects o where o.id = object_id and public.is_project_member(o.project_id)))
with check (exists(select 1 from public.objects o where o.id = object_id and public.has_project_role(o.project_id, array['owner','admin','editor']::public.project_role[])));

create policy geofences_access on public.geofences for all
using (public.is_project_member(project_id))
with check (public.has_project_role(project_id, array['owner','admin','editor']::public.project_role[]));

create policy notifications_select on public.notifications for select
using (user_id = auth.uid());
create policy notifications_update on public.notifications for update
using (user_id = auth.uid()) with check (user_id = auth.uid());

create policy audit_select on public.audit_logs for select
using (project_id is not null and public.has_project_role(project_id, array['owner','admin']::public.project_role[]));

-- Grants to authenticated clients (RLS still applies).
grant usage on schema public to authenticated;
grant select, insert, update, delete on all tables in schema public to authenticated;
grant execute on function public.has_project_role(uuid, public.project_role[]) to authenticated;
grant execute on function public.is_project_member(uuid) to authenticated;

-- Nearby search RPC: distance is meters.
create or replace function public.nearby_objects(
    p_lat double precision,
    p_lon double precision,
    p_radius_m double precision default 500
)
returns table (
    id uuid, project_id uuid, name text, category text,
    latitude double precision, longitude double precision, distance_m double precision
)
language sql stable security invoker
as $$
    select o.id, o.project_id, o.name, o.category, o.latitude, o.longitude,
           extensions.st_distance(
             o.location,
             extensions.st_setsrid(extensions.st_makepoint(p_lon, p_lat), 4326)::extensions.geography
           ) as distance_m
    from public.objects o
    where o.location is not null
      and public.is_project_member(o.project_id)
      and extensions.st_dwithin(
          o.location,
          extensions.st_setsrid(extensions.st_makepoint(p_lon, p_lat), 4326)::extensions.geography,
          p_radius_m
      )
    order by distance_m asc;
$$;
grant execute on function public.nearby_objects(double precision, double precision, double precision) to authenticated;
