-- A reviewed import is atomic, traceable and inaccessible to unauthorized members.
begin;
insert into auth.users(id, email) values
 ('10000000-0000-0000-0000-000000000001', 'owner-import@example.com'),
 ('10000000-0000-0000-0000-000000000002', 'importer@example.com'),
 ('10000000-0000-0000-0000-000000000003', 'other@example.com');
insert into public.projects(id, name, owner_id) values
 ('20000000-0000-0000-0000-000000000001', 'Test', '10000000-0000-0000-0000-000000000001');
insert into public.project_members(project_id, user_id, role) values
 ('20000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000002', 'editor'),
 ('20000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000003', 'viewer');
insert into public.project_features values ('20000000-0000-0000-0000-000000000001', 'water', true);
insert into public.project_feature_grants(project_id, user_id, feature, permission) values
 ('20000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000002', 'water', 'view'),
 ('20000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000002', 'water', 'import'),
 ('20000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000002', 'water', 'edit');
insert into public.buildings(id, project_id, name) values
 ('30000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', 'Edificio');
insert into public.water_units(id, project_id, building_id, label) values
 ('40000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001', 'A1');
set local role authenticated;
select set_config('request.jwt.claim.sub', '10000000-0000-0000-0000-000000000002', true);
insert into public.water_imports(id, project_id, building_id, source_name, source_type) values
 ('50000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001', 'tabella.xlsx', 'xlsx');
insert into public.water_import_rows(id, import_id, project_id, building_id, source_row, unit_id, kind, period_start, period_end, amount) values
 ('60000000-0000-0000-0000-000000000001', '50000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001', 'Foglio 1:2', '40000000-0000-0000-0000-000000000001', 'cold', '2024-01-01', '2024-12-31', 12.4),
 ('60000000-0000-0000-0000-000000000002', '50000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001', 'Foglio 1:3', '40000000-0000-0000-0000-000000000001', 'hot', '2024-01-01', '2024-12-31', null);
do $$ begin
  begin
    perform public.confirm_water_import('50000000-0000-0000-0000-000000000001');
    raise exception 'incomplete import accepted';
  exception when check_violation then null; end;
  if exists (select 1 from public.water_consumption) then raise exception 'partial import saved'; end if;
end $$;
update public.water_import_rows set amount = 7.2 where id = '60000000-0000-0000-0000-000000000002';
select public.confirm_water_import('50000000-0000-0000-0000-000000000001');
do $$ begin
  if (select count(*) from public.water_consumption where import_row_id is not null) <> 2 then
    raise exception 'confirmed rows or provenance missing'; end if;
  begin
    perform public.confirm_water_import('50000000-0000-0000-0000-000000000001');
    raise exception 'confirmed twice';
  exception when check_violation then null; end;
  begin
    update public.water_import_rows set amount = 20 where id = '60000000-0000-0000-0000-000000000001';
    raise exception 'confirmed source changed';
  exception when check_violation then null; end;
end $$;
insert into public.water_imports(id, project_id, building_id, source_name, source_type) values
 ('50000000-0000-0000-0000-000000000002', '20000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001', 'duplicato.xlsx', 'xlsx');
insert into public.water_import_rows(import_id, project_id, building_id, source_row, unit_id, kind, period_start, period_end, amount) values
 ('50000000-0000-0000-0000-000000000002', '20000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001', '1', '40000000-0000-0000-0000-000000000001', 'cold', '2025-01-01', '2025-12-31', 4),
 ('50000000-0000-0000-0000-000000000002', '20000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001', '2', '40000000-0000-0000-0000-000000000001', 'cold', '2024-01-01', '2024-12-31', 12);
do $$ begin
  begin
    perform public.confirm_water_import('50000000-0000-0000-0000-000000000002');
    raise exception 'duplicate consumption accepted';
  exception when unique_violation then null; end;
  if (select count(*) from public.water_consumption) <> 2 or
     (select confirmed_at is not null from public.water_imports where id = '50000000-0000-0000-0000-000000000002') then
    raise exception 'duplicate confirmation was not atomic'; end if;
  begin
    update public.water_consumption set import_row_id = null where id is not null;
    raise exception 'provenance modified directly';
  exception when insufficient_privilege then null; end;
end $$;
select set_config('request.jwt.claim.sub', '10000000-0000-0000-0000-000000000003', true);
do $$ begin
  if exists (select 1 from public.water_imports) or exists (select 1 from public.water_import_rows) then
    raise exception 'unauthorized user can view imports'; end if;
  begin
    perform public.confirm_water_import('50000000-0000-0000-0000-000000000001');
    raise exception 'unauthorized confirmation';
  exception when insufficient_privilege then null; end;
end $$;
rollback;
