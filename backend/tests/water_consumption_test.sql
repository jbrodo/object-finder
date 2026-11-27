-- Period consumption is scoped to the project and requires explicit water permissions.
begin;
insert into auth.users(id, email) values
 ('00000000-0000-0000-0000-0000000000a1', 'water-owner@example.com'),
 ('00000000-0000-0000-0000-0000000000a2', 'water-editor@example.com'),
 ('00000000-0000-0000-0000-0000000000a3', 'water-viewer@example.com'),
 ('00000000-0000-0000-0000-0000000000a4', 'water-denied@example.com');
insert into public.projects(id, name, owner_id) values
 ('00000000-0000-0000-0000-0000000000f1', 'Uno', '00000000-0000-0000-0000-0000000000a1'),
 ('00000000-0000-0000-0000-0000000000f2', 'Due', '00000000-0000-0000-0000-0000000000a1');
insert into public.project_members(project_id, user_id, role) values
 ('00000000-0000-0000-0000-0000000000f1', '00000000-0000-0000-0000-0000000000a2', 'editor'),
 ('00000000-0000-0000-0000-0000000000f1', '00000000-0000-0000-0000-0000000000a3', 'viewer'),
 ('00000000-0000-0000-0000-0000000000f1', '00000000-0000-0000-0000-0000000000a4', 'viewer');
insert into public.project_features(project_id, feature, enabled) values
 ('00000000-0000-0000-0000-0000000000f1', 'water', true),
 ('00000000-0000-0000-0000-0000000000f2', 'water', true);
insert into public.project_feature_grants(project_id, user_id, feature, permission) values
 ('00000000-0000-0000-0000-0000000000f1', '00000000-0000-0000-0000-0000000000a2', 'water', 'view'),
 ('00000000-0000-0000-0000-0000000000f1', '00000000-0000-0000-0000-0000000000a2', 'water', 'edit'),
 ('00000000-0000-0000-0000-0000000000f1', '00000000-0000-0000-0000-0000000000a3', 'water', 'view');
insert into public.buildings(id, project_id, name) values
 ('00000000-0000-0000-0000-00000000b001', '00000000-0000-0000-0000-0000000000f1', 'Uno'),
 ('00000000-0000-0000-0000-00000000b002', '00000000-0000-0000-0000-0000000000f2', 'Due');
set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-0000000000a2', true);
insert into public.water_units(id, project_id, building_id, label, section) values
 ('00000000-0000-0000-0000-00000000c001', '00000000-0000-0000-0000-0000000000f1', '00000000-0000-0000-0000-00000000b001', 'A1', 'A');
insert into public.water_consumption(id, project_id, building_id, unit_id, kind, period_start, period_end, amount) values
 ('00000000-0000-0000-0000-00000000d001', '00000000-0000-0000-0000-0000000000f1', '00000000-0000-0000-0000-00000000b001', '00000000-0000-0000-0000-00000000c001', 'cold', '2024-01-01', '2024-12-31', 12.345);
do $$ begin
  begin
    insert into public.water_consumption(project_id, building_id, unit_id, kind, period_start, period_end, amount) values
     ('00000000-0000-0000-0000-0000000000f1', '00000000-0000-0000-0000-00000000b001', '00000000-0000-0000-0000-00000000c001', 'cold', '2024-01-01', '2024-12-31', 3);
    raise exception 'duplicate accepted';
  exception when unique_violation then null; end;
  begin
    insert into public.water_units(project_id, building_id, label) values
     ('00000000-0000-0000-0000-0000000000f1', '00000000-0000-0000-0000-00000000b002', 'bad');
    raise exception 'cross-project building accepted';
  exception when foreign_key_violation then null; end;
  begin
    insert into public.water_consumption(project_id, building_id, unit_id, kind, period_start, period_end, amount) values
     ('00000000-0000-0000-0000-0000000000f1', '00000000-0000-0000-0000-00000000b001', '00000000-0000-0000-0000-00000000c001', 'hot', '2025-01-01', '2024-12-31', -1);
    raise exception 'invalid period or negative consumption accepted';
  exception when check_violation then null; end;
end $$;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-0000000000a3', true);
do $$ begin
  if (select count(*) from public.water_consumption) <> 1 then raise exception 'authorized viewer denied'; end if;
  begin
    insert into public.water_units(project_id, building_id, label) values
     ('00000000-0000-0000-0000-0000000000f1', '00000000-0000-0000-0000-00000000b001', 'A2');
    raise exception 'viewer wrote data';
  exception when insufficient_privilege then null; end;
end $$;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-0000000000a4', true);
do $$ begin
  if exists (select 1 from public.water_consumption) or exists (select 1 from public.water_units) then
    raise exception 'ungranted member saw water data'; end if;
end $$;
reset role;
update public.project_features set enabled = false where project_id = '00000000-0000-0000-0000-0000000000f1' and feature = 'water';
set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-0000000000a2', true);
do $$ begin
  if exists (select 1 from public.water_consumption) then raise exception 'disabled water visible'; end if;
end $$;
rollback;
