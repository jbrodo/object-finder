-- Reviewable source rows; files and OCR extraction are implemented separately.
create table public.water_imports (
    id uuid primary key default gen_random_uuid(),
    project_id uuid not null,
    building_id uuid not null,
    source_name text not null check (char_length(btrim(source_name)) between 1 and 255),
    source_type text not null check (source_type in ('xlsx', 'photo', 'manual')),
    created_by uuid not null default auth.uid() references auth.users(id),
    created_at timestamptz not null default now(),
    confirmed_at timestamptz,
    confirmed_by uuid references auth.users(id),
    check ((confirmed_at is null) = (confirmed_by is null)),
    foreign key (building_id, project_id) references public.buildings(id, project_id),
    unique (id, project_id, building_id)
);
create index water_imports_project_building_idx on public.water_imports(project_id, building_id);

create table public.water_import_rows (
    id uuid primary key default gen_random_uuid(),
    import_id uuid not null,
    project_id uuid not null,
    building_id uuid not null,
    source_row text not null check (char_length(btrim(source_row)) between 1 and 200),
    unit_id uuid,
    kind text check (kind in ('cold', 'hot')),
    period_start date,
    period_end date,
    amount numeric check (amount >= 0 and amount < 1000000000 and scale(amount) <= 3),
    foreign key (import_id, project_id, building_id) references public.water_imports(id, project_id, building_id) on delete cascade,
    foreign key (unit_id, building_id, project_id) references public.water_units(id, building_id, project_id),
    check (period_start is null or period_end is null or period_start <= period_end),
    unique (id, project_id, building_id)
);
create index water_import_rows_import_idx on public.water_import_rows(import_id);
alter table public.water_consumption add column import_row_id uuid;
alter table public.water_consumption add constraint water_consumption_import_row_fk
    foreign key (import_row_id, project_id, building_id) references public.water_import_rows(id, project_id, building_id);
create unique index water_consumption_import_row_idx on public.water_consumption(import_row_id) where import_row_id is not null;
-- Only the confirmation function may assign provenance; existing manual fields remain writable.
revoke insert, update on public.water_consumption from authenticated;
grant insert (id, project_id, building_id, unit_id, kind, period_start, period_end, amount, created_at, updated_at)
    on public.water_consumption to authenticated;
grant update (project_id, building_id, unit_id, kind, period_start, period_end, amount, updated_at)
    on public.water_consumption to authenticated;

alter table public.water_imports enable row level security;
alter table public.water_import_rows enable row level security;
create policy water_imports_read on public.water_imports for select to authenticated
    using (public.has_feature_permission(project_id, 'water', 'view') and
           public.has_feature_permission(project_id, 'water', 'import'));
create policy water_imports_insert on public.water_imports for insert to authenticated
    with check (public.has_feature_permission(project_id, 'water', 'view') and
                public.has_feature_permission(project_id, 'water', 'import') and
                created_by = (select auth.uid()) and confirmed_at is null and confirmed_by is null);
create policy water_imports_update on public.water_imports for update to authenticated
    using (created_by = (select auth.uid()) and confirmed_at is null and
           public.has_feature_permission(project_id, 'water', 'view') and public.has_feature_permission(project_id, 'water', 'import'))
    with check (created_by = (select auth.uid()) and confirmed_at is null and
                public.has_feature_permission(project_id, 'water', 'view') and public.has_feature_permission(project_id, 'water', 'import'));
create policy water_import_rows_read on public.water_import_rows for select to authenticated
    using (public.has_feature_permission(project_id, 'water', 'view') and public.has_feature_permission(project_id, 'water', 'import'));
create policy water_import_rows_insert on public.water_import_rows for insert to authenticated
    with check (public.has_feature_permission(project_id, 'water', 'view') and public.has_feature_permission(project_id, 'water', 'import'));
create policy water_import_rows_update on public.water_import_rows for update to authenticated
    using (public.has_feature_permission(project_id, 'water', 'view') and public.has_feature_permission(project_id, 'water', 'import'))
    with check (public.has_feature_permission(project_id, 'water', 'view') and public.has_feature_permission(project_id, 'water', 'import'));
create policy water_import_rows_delete on public.water_import_rows for delete to authenticated
    using (public.has_feature_permission(project_id, 'water', 'view') and public.has_feature_permission(project_id, 'water', 'import'));
grant select, insert on public.water_imports to authenticated;
grant update (source_name) on public.water_imports to authenticated;
grant select, insert, update, delete on public.water_import_rows to authenticated;

-- Serialize edits with confirmation and preserve the original rows once confirmed.
create schema if not exists water_import_private;
revoke all on schema water_import_private from public, anon;
grant usage on schema water_import_private to authenticated;
create function water_import_private.guard_water_import_row() returns trigger language plpgsql security definer set search_path = '' as $$
declare v_import public.water_imports%rowtype;
begin
    if tg_op = 'UPDATE' and (new.import_id, new.project_id, new.building_id) is distinct from
        (old.import_id, old.project_id, old.building_id) then
        raise exception 'cannot move import row' using errcode = '23514';
    end if;
    select * into v_import from public.water_imports
      where id = case when tg_op = 'DELETE' then old.import_id else new.import_id end for update;
    if not found or v_import.created_by is distinct from auth.uid() or not (
        public.has_feature_permission(v_import.project_id, 'water', 'view') and
        public.has_feature_permission(v_import.project_id, 'water', 'import')) then
        raise exception 'not allowed to edit import' using errcode = '42501';
    end if;
    if v_import.confirmed_at is not null then raise exception 'import already confirmed' using errcode = '23514'; end if;
    if tg_op = 'DELETE' then return old; else return new; end if;
end;
$$;
revoke all on function water_import_private.guard_water_import_row() from public, anon, authenticated;
create trigger water_import_rows_guard before insert or update or delete on public.water_import_rows
    for each row execute function water_import_private.guard_water_import_row();

-- Definer is isolated in a non-exposed schema; it checks membership, permissions and ownership.
create function water_import_private.confirm_water_import(p_id uuid) returns integer
language plpgsql security definer set search_path = '' as $$
declare v_import public.water_imports%rowtype; v_count integer;
begin
    if auth.uid() is null then raise exception 'not authorized' using errcode = '42501'; end if;
    select * into v_import from public.water_imports where id = p_id for update;
    if not found or v_import.created_by is distinct from auth.uid() or not (
        public.has_feature_permission(v_import.project_id, 'water', 'view') and
        public.has_feature_permission(v_import.project_id, 'water', 'import') and
        public.has_feature_permission(v_import.project_id, 'water', 'edit')
    ) then raise exception 'not authorized' using errcode = '42501'; end if;
    if v_import.confirmed_at is not null then raise exception 'import already confirmed' using errcode = '23514'; end if;
    if not exists (select 1 from public.water_import_rows where import_id = p_id) or
       exists (select 1 from public.water_import_rows where import_id = p_id and
           (unit_id is null or kind is null or period_start is null or period_end is null or amount is null)) then
        raise exception 'import has incomplete rows' using errcode = '23514';
    end if;
    insert into public.water_consumption(project_id, building_id, unit_id, kind, period_start, period_end, amount, import_row_id)
    select project_id, building_id, unit_id, kind, period_start, period_end, amount, id
    from public.water_import_rows where import_id = p_id;
    get diagnostics v_count = row_count;
    update public.water_imports set confirmed_at = now(), confirmed_by = auth.uid() where id = p_id;
    return v_count;
end;
$$;
revoke all on function water_import_private.confirm_water_import(uuid) from public, anon;
grant execute on function water_import_private.confirm_water_import(uuid) to authenticated;
create function public.confirm_water_import(p_id uuid) returns integer
language sql security invoker set search_path = '' as $$ select water_import_private.confirm_water_import(p_id); $$;
revoke all on function public.confirm_water_import(uuid) from public, anon;
grant execute on function public.confirm_water_import(uuid) to authenticated;
