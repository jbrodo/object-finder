-- Staging is atomic, keeps source labels and never confirms consumption.
begin;
insert into auth.users(id, email) values
 ('11000000-0000-0000-0000-000000000001', 'stage-owner@example.com'),
 ('11000000-0000-0000-0000-000000000002', 'stage-importer@example.com'),
 ('11000000-0000-0000-0000-000000000003', 'stage-other@example.com');
insert into public.projects(id, name, owner_id) values
 ('22000000-0000-0000-0000-000000000001', 'Test', '11000000-0000-0000-0000-000000000001');
insert into public.project_members(project_id, user_id, role) values
 ('22000000-0000-0000-0000-000000000001', '11000000-0000-0000-0000-000000000002', 'editor'),
 ('22000000-0000-0000-0000-000000000001', '11000000-0000-0000-0000-000000000003', 'viewer');
insert into public.project_features values ('22000000-0000-0000-0000-000000000001', 'water', true);
insert into public.project_feature_grants(project_id, user_id, feature, permission) values
 ('22000000-0000-0000-0000-000000000001', '11000000-0000-0000-0000-000000000002', 'water', 'view'),
 ('22000000-0000-0000-0000-000000000001', '11000000-0000-0000-0000-000000000002', 'water', 'import');
insert into public.buildings(id, project_id, name) values
 ('33000000-0000-0000-0000-000000000001', '22000000-0000-0000-0000-000000000001', 'Casa');
insert into public.water_units(id, project_id, building_id, label) values
 ('44000000-0000-0000-0000-000000000001', '22000000-0000-0000-0000-000000000001', '33000000-0000-0000-0000-000000000001', 'A1');
set local role authenticated;
select set_config('request.jwt.claim.sub', '11000000-0000-0000-0000-000000000002', true);
select public.stage_water_candidates(
 '55000000-0000-0000-0000-000000000001', '22000000-0000-0000-0000-000000000001',
 '33000000-0000-0000-0000-000000000001', 'foto.jpg', 'photo',
 '[{"source_row":"tabella 1, riga 2, colonna 2","unit_label":"A1","period_label":"2023","kind":"cold","raw_amount":"12,5","amount":"12.5","warnings":["ocr_requires_review"]}]'::jsonb);
do $$ begin
  if not exists (select 1 from public.water_import_rows where import_id='55000000-0000-0000-0000-000000000001'
      and source_unit_label='A1' and source_period_label='2023' and source_amount='12,5'
      and source_warnings='["ocr_requires_review"]'::jsonb and unit_id is null and period_start is null) then
    raise exception 'source fields missing or values guessed'; end if;
  if exists (select 1 from public.water_consumption) then raise exception 'staging published values'; end if;
  begin
    update public.water_import_rows set source_amount = '99' where import_id='55000000-0000-0000-0000-000000000001';
    raise exception 'source evidence changed';
  exception when insufficient_privilege then null; end;
  begin
    perform public.stage_water_candidates('55000000-0000-0000-0000-000000000001',
     '22000000-0000-0000-0000-000000000001','33000000-0000-0000-0000-000000000001', 'foto.jpg', 'photo', '[]'::jsonb);
    raise exception 'empty or repeated staging accepted';
  exception when check_violation or unique_violation then null; end;
  begin
    perform public.stage_water_candidates('55000000-0000-0000-0000-000000000002',
     '22000000-0000-0000-0000-000000000001','33000000-0000-0000-0000-000000000001', 'foto.jpg', 'photo',
     '[{"source_row":"ok","kind":"cold"},{"source_row":"bad","kind":"other"}]'::jsonb);
    raise exception 'invalid batch accepted';
  exception when check_violation then null; end;
  if exists (select 1 from public.water_imports where id='55000000-0000-0000-0000-000000000002') then
    raise exception 'failed batch left an import'; end if;
end $$;
reset role;
insert into public.project_feature_grants(project_id, user_id, feature, permission) values
 ('22000000-0000-0000-0000-000000000001', '11000000-0000-0000-0000-000000000002', 'water', 'edit');
set local role authenticated;
update public.water_import_rows set unit_id='44000000-0000-0000-0000-000000000001',
    period_start='2023-01-01', period_end='2023-12-31', amount=12.6
where import_id='55000000-0000-0000-0000-000000000001';
select public.confirm_water_import('55000000-0000-0000-0000-000000000001');
do $$ begin
  if not exists (select 1 from public.water_consumption c join public.water_import_rows r on r.id=c.import_row_id
      where c.amount=12.6 and r.source_amount='12,5' and r.source_unit_label='A1') then
    raise exception 'reviewed value or original source lost'; end if;
end $$;
select set_config('request.jwt.claim.sub', '11000000-0000-0000-0000-000000000003', true);
do $$ begin
  begin
    perform public.stage_water_candidates('55000000-0000-0000-0000-000000000003',
     '22000000-0000-0000-0000-000000000001','33000000-0000-0000-0000-000000000001', 'x', 'photo',
     '[{"source_row":"1"}]'::jsonb);
    raise exception 'unauthorized stage accepted';
  exception when insufficient_privilege then null; end;
end $$;
rollback;
