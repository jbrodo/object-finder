-- Optional project features and explicit per-member permissions.
-- Existing inventory policies are intentionally unchanged. Features are disabled by default.
create table public.project_features (
    project_id uuid not null references public.projects(id) on delete cascade,
    feature text not null check (feature in ('water', 'inventory_analytics')),
    enabled boolean not null default false,
    primary key (project_id, feature)
);

create table public.project_feature_grants (
    project_id uuid not null,
    user_id uuid not null,
    feature text not null,
    permission text not null check (permission in ('view', 'import', 'edit', 'manage')),
    primary key (project_id, user_id, feature, permission),
    foreign key (project_id, user_id) references public.project_members(project_id, user_id) on delete cascade,
    foreign key (project_id, feature) references public.project_features(project_id, feature) on delete cascade
);
create index project_feature_grants_feature_idx on public.project_feature_grants(project_id, feature);

alter table public.project_features enable row level security;
alter table public.project_feature_grants enable row level security;

create policy project_features_read on public.project_features for select to authenticated
    using (public.is_project_member(project_id));
create policy project_features_manage on public.project_features for all to authenticated
    using (public.has_project_role(project_id, array['owner','admin']::public.project_role[]))
    with check (public.has_project_role(project_id, array['owner','admin']::public.project_role[]));
create policy project_feature_grants_read on public.project_feature_grants for select to authenticated
    using (public.is_project_member(project_id) and
        (user_id = (select auth.uid()) or public.has_project_role(project_id, array['owner','admin']::public.project_role[])));
create policy project_feature_grants_manage on public.project_feature_grants for all to authenticated
    using (public.has_project_role(project_id, array['owner','admin']::public.project_role[]))
    with check (public.has_project_role(project_id, array['owner','admin']::public.project_role[]));

grant select, insert, update, delete on public.project_features, public.project_feature_grants to authenticated;

-- Security invoker: both feature and grant queries remain subject to their RLS policies.
create function public.has_feature_permission(p_project uuid, p_feature text, p_permission text)
returns boolean language sql stable security invoker set search_path = '' as $$
    select exists (
        select 1 from public.project_features f
        join public.project_feature_grants g on g.project_id = f.project_id and g.feature = f.feature
        where f.project_id = p_project and f.feature = p_feature and f.enabled
          and g.user_id = (select auth.uid()) and g.permission = p_permission
          and public.is_project_member(p_project)
    );
$$;
revoke all on function public.has_feature_permission(uuid, text, text) from public, anon;
grant execute on function public.has_feature_permission(uuid, text, text) to authenticated;

-- Composite foreign key prevents a building linking to a place in another project.
alter table public.places add constraint places_id_project_unique unique (id, project_id);
create table public.buildings (
    id uuid primary key default gen_random_uuid(),
    project_id uuid not null references public.projects(id) on delete cascade,
    place_id uuid,
    name text not null check (char_length(btrim(name)) between 1 and 120),
    address text not null default '' check (char_length(address) <= 500),
    latitude double precision check (latitude between -90 and 90),
    longitude double precision check (longitude between -180 and 180),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    check ((latitude is null) = (longitude is null)),
    foreign key (place_id, project_id) references public.places(id, project_id) on delete set null (place_id)
);
create index buildings_project_idx on public.buildings(project_id);
create index buildings_place_idx on public.buildings(place_id, project_id) where place_id is not null;
create function public.touch_building_updated_at() returns trigger
language plpgsql set search_path = '' as $$
begin
    new.updated_at = now();
    return new;
end;
$$;
create trigger buildings_touch_updated_at before update on public.buildings
    for each row execute function public.touch_building_updated_at();
alter table public.buildings enable row level security;
create policy buildings_read on public.buildings for select to authenticated
    using (public.is_project_member(project_id));
create policy buildings_insert on public.buildings for insert to authenticated
    with check (public.has_project_role(project_id, array['owner','admin','editor']::public.project_role[]));
create policy buildings_update on public.buildings for update to authenticated
    using (public.has_project_role(project_id, array['owner','admin','editor']::public.project_role[]))
    with check (public.has_project_role(project_id, array['owner','admin','editor']::public.project_role[]));
create policy buildings_delete on public.buildings for delete to authenticated
    using (public.has_project_role(project_id, array['owner','admin']::public.project_role[]));
grant select, insert, update, delete on public.buildings to authenticated;
