-- Feature permissions are opt-in; project membership is always required.
begin;
insert into auth.users(id, email) values
 ('00000000-0000-0000-0000-0000000000a1', 'owner@example.com'),
 ('00000000-0000-0000-0000-0000000000a2', 'editor@example.com'),
 ('00000000-0000-0000-0000-0000000000a3', 'viewer@example.com');
insert into public.projects(id, name, owner_id) values
 ('00000000-0000-0000-0000-0000000000f1', 'Uno', '00000000-0000-0000-0000-0000000000a1'),
 ('00000000-0000-0000-0000-0000000000f2', 'Due', '00000000-0000-0000-0000-0000000000a1');
insert into public.project_members(project_id, user_id, role) values
 ('00000000-0000-0000-0000-0000000000f1', '00000000-0000-0000-0000-0000000000a2', 'editor'),
 ('00000000-0000-0000-0000-0000000000f1', '00000000-0000-0000-0000-0000000000a3', 'viewer');
insert into public.places(id, project_id, name) values
 ('00000000-0000-0000-0000-00000000c001', '00000000-0000-0000-0000-0000000000f1', 'Casa'),
 ('00000000-0000-0000-0000-00000000c002', '00000000-0000-0000-0000-0000000000f2', 'Altrove');

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-0000000000a1', true);
insert into public.project_features(project_id, feature, enabled) values
 ('00000000-0000-0000-0000-0000000000f1', 'water', true);
insert into public.project_feature_grants(project_id, user_id, feature, permission) values
 ('00000000-0000-0000-0000-0000000000f1', '00000000-0000-0000-0000-0000000000a2', 'water', 'view');
insert into public.buildings(id, project_id, place_id, name) values
 ('00000000-0000-0000-0000-00000000b001', '00000000-0000-0000-0000-0000000000f1', '00000000-0000-0000-0000-00000000c001', 'Casa');
do $$ begin
  begin
    insert into public.buildings(project_id, place_id, name) values
     ('00000000-0000-0000-0000-0000000000f1', '00000000-0000-0000-0000-00000000c002', 'Errato');
    raise exception 'cross-project place accepted';
  exception when foreign_key_violation then null; end;
end $$;

select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-0000000000a2', true);
do $$ begin
  if not public.has_feature_permission('00000000-0000-0000-0000-0000000000f1', 'water', 'view') then
    raise exception 'explicit permission denied'; end if;
  if public.has_feature_permission('00000000-0000-0000-0000-0000000000f1', 'water', 'import') then
    raise exception 'ungranted permission allowed'; end if;
  if (select count(*) from public.buildings) <> 1 then raise exception 'member cannot see building'; end if;
  begin
    insert into public.project_feature_grants(project_id, user_id, feature, permission) values
     ('00000000-0000-0000-0000-0000000000f1', '00000000-0000-0000-0000-0000000000a2', 'water', 'import');
    raise exception 'member granted themselves permission';
  exception when insufficient_privilege then null; end;
end $$;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-0000000000a3', true);
do $$ begin
  if public.has_feature_permission('00000000-0000-0000-0000-0000000000f1', 'water', 'view') then
    raise exception 'ungranted user allowed'; end if;
  if (select count(*) from public.project_feature_grants) <> 0 then
    raise exception 'other user grants visible'; end if;
  if (select count(*) from public.buildings where project_id = '00000000-0000-0000-0000-0000000000f2') <> 0 then
    raise exception 'other project buildings visible'; end if;
end $$;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-0000000000a1', true);
delete from public.places where id = '00000000-0000-0000-0000-00000000c001';
do $$ begin
  if not exists (select 1 from public.buildings where id = '00000000-0000-0000-0000-00000000b001' and place_id is null) then
    raise exception 'deleting place lost building'; end if;
end $$;
update public.project_features set enabled = false where project_id = '00000000-0000-0000-0000-0000000000f1' and feature = 'water';
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-0000000000a2', true);
do $$ begin
  if public.has_feature_permission('00000000-0000-0000-0000-0000000000f1', 'water', 'view') then
    raise exception 'disabled feature allowed'; end if;
end $$;
reset role;
delete from public.project_members where project_id = '00000000-0000-0000-0000-0000000000f1' and user_id = '00000000-0000-0000-0000-0000000000a2';
set local role authenticated;
do $$ begin
  if public.has_feature_permission('00000000-0000-0000-0000-0000000000f1', 'water', 'view') then
    raise exception 'removed member retained permission'; end if;
end $$;
rollback;
