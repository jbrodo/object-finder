begin;
insert into auth.users(id, email) values ('12000000-0000-0000-0000-000000000001', 'review@example.com');
insert into public.projects(id, name, owner_id) values ('22000000-0000-0000-0000-000000000001', 'Casa', '12000000-0000-0000-0000-000000000001');
insert into public.project_features values ('22000000-0000-0000-0000-000000000001', 'water', true);
insert into public.project_feature_grants(project_id, user_id, feature, permission) values
 ('22000000-0000-0000-0000-000000000001', '12000000-0000-0000-0000-000000000001', 'water', 'view'),
 ('22000000-0000-0000-0000-000000000001', '12000000-0000-0000-0000-000000000001', 'water', 'import'),
 ('22000000-0000-0000-0000-000000000001', '12000000-0000-0000-0000-000000000001', 'water', 'edit');
insert into public.buildings(id, project_id, name) values ('33000000-0000-0000-0000-000000000001', '22000000-0000-0000-0000-000000000001', 'Casa');
insert into public.water_units(id, project_id, building_id, label) values ('44000000-0000-0000-0000-000000000001', '22000000-0000-0000-0000-000000000001', '33000000-0000-0000-0000-000000000001', 'A1');
set local role authenticated;
select set_config('request.jwt.claim.sub', '12000000-0000-0000-0000-000000000001', true);
insert into public.water_imports(id, project_id, building_id, source_name, source_type) values ('55000000-0000-0000-0000-000000000001','22000000-0000-0000-0000-000000000001','33000000-0000-0000-0000-000000000001','foto','photo');
insert into public.water_import_rows(id, import_id, project_id, building_id, source_row, unit_id, kind, period_start, period_end, amount) values
 ('66000000-0000-0000-0000-000000000001','55000000-0000-0000-0000-000000000001','22000000-0000-0000-0000-000000000001','33000000-0000-0000-0000-000000000001','1','44000000-0000-0000-0000-000000000001','cold','2024-01-01','2024-12-31',12.5);
do $$ begin
  begin
    perform public.confirm_water_import_reviewed('55000000-0000-0000-0000-000000000001',
      '[{"id":"66000000-0000-0000-0000-000000000001","unit_id":"44000000-0000-0000-0000-000000000001","kind":"cold","period_start":"2024-01-01","period_end":"2024-12-31","amount":"12.6"}]');
    raise exception 'stale review accepted';
  exception when serialization_failure then null; end;
  if exists (select 1 from public.water_consumption) then raise exception 'stale review published'; end if;
end $$;
select public.confirm_water_import_reviewed('55000000-0000-0000-0000-000000000001',
 '[{"id":"66000000-0000-0000-0000-000000000001","unit_id":"44000000-0000-0000-0000-000000000001","kind":"cold","period_start":"2024-01-01","period_end":"2024-12-31","amount":"12.5"}]');
do $$ begin if (select count(*) from public.water_consumption) <> 1 then raise exception 'confirmation failed'; end if; end $$;
rollback;
