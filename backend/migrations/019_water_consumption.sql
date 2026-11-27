-- Confirmed period consumption, measured in cubic metres. OCR staging is a later step.
-- Visibility is currently project-wide for members explicitly granted water/view.
alter table public.buildings add constraint buildings_id_project_unique unique (id, project_id);

create table public.water_units (
    id uuid primary key default gen_random_uuid(),
    project_id uuid not null,
    building_id uuid not null,
    label text not null check (char_length(btrim(label)) between 1 and 120),
    section text not null default '' check (char_length(section) <= 80),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    foreign key (building_id, project_id) references public.buildings(id, project_id),
    unique (id, building_id, project_id),
    unique (building_id, section, label)
);
create index water_units_project_building_idx on public.water_units(project_id, building_id);

create table public.water_consumption (
    id uuid primary key default gen_random_uuid(),
    project_id uuid not null,
    building_id uuid not null,
    unit_id uuid not null,
    kind text not null check (kind in ('cold', 'hot')),
    period_start date not null,
    period_end date not null,
    amount numeric not null check (amount >= 0 and amount < 1000000000 and scale(amount) <= 3),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    check (period_start <= period_end),
    foreign key (unit_id, building_id, project_id) references public.water_units(id, building_id, project_id),
    unique (unit_id, kind, period_start, period_end)
);
create index water_consumption_project_building_period_idx
    on public.water_consumption(project_id, building_id, period_start, period_end);

create trigger water_units_touch_updated_at before update on public.water_units
    for each row execute function public.touch_building_updated_at();
create trigger water_consumption_touch_updated_at before update on public.water_consumption
    for each row execute function public.touch_building_updated_at();

alter table public.water_units enable row level security;
alter table public.water_consumption enable row level security;

create policy water_units_read on public.water_units for select to authenticated
    using (public.has_feature_permission(project_id, 'water', 'view'));
create policy water_units_insert on public.water_units for insert to authenticated
    with check (public.has_feature_permission(project_id, 'water', 'edit'));
create policy water_units_update on public.water_units for update to authenticated
    using (public.has_feature_permission(project_id, 'water', 'edit'))
    with check (public.has_feature_permission(project_id, 'water', 'edit'));
create policy water_units_delete on public.water_units for delete to authenticated
    using (public.has_feature_permission(project_id, 'water', 'manage'));

create policy water_consumption_read on public.water_consumption for select to authenticated
    using (public.has_feature_permission(project_id, 'water', 'view'));
create policy water_consumption_insert on public.water_consumption for insert to authenticated
    with check (public.has_feature_permission(project_id, 'water', 'edit'));
create policy water_consumption_update on public.water_consumption for update to authenticated
    using (public.has_feature_permission(project_id, 'water', 'edit'))
    with check (public.has_feature_permission(project_id, 'water', 'edit'));
create policy water_consumption_delete on public.water_consumption for delete to authenticated
    using (public.has_feature_permission(project_id, 'water', 'manage'));

grant select, insert, update, delete on public.water_units, public.water_consumption to authenticated;
