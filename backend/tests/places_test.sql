-- Test SQL dei luoghi (migration 015): albero coerente, niente cicli, cancellazione non distruttiva.
begin;
insert into auth.users(id, email, email_confirmed_at) values ('00000000-0000-0000-0000-0000000000a1', 'owner@example.com', now());
insert into public.projects(id, name, owner_id) values
    ('00000000-0000-0000-0000-0000000000f1', 'Casa', '00000000-0000-0000-0000-0000000000a1'),
    ('00000000-0000-0000-0000-0000000000f2', 'Ufficio', '00000000-0000-0000-0000-0000000000a1');
insert into public.places(id, project_id, parent_id, name) values
    ('00000000-0000-0000-0000-00000000c001', '00000000-0000-0000-0000-0000000000f1', null, 'Casa'),
    ('00000000-0000-0000-0000-00000000c002', '00000000-0000-0000-0000-0000000000f1', '00000000-0000-0000-0000-00000000c001', 'Garage'),
    ('00000000-0000-0000-0000-00000000c003', '00000000-0000-0000-0000-0000000000f2', null, 'Scrivania');
insert into public.objects(id, project_id, name, place_id) values
    ('00000000-0000-0000-0000-0000000000b1', '00000000-0000-0000-0000-0000000000f1', 'Trapano', '00000000-0000-0000-0000-00000000c002');

do $$
begin
    begin
        update public.places set parent_id = '00000000-0000-0000-0000-00000000c002'
        where id = '00000000-0000-0000-0000-00000000c001';
        raise exception 'ciclo accettato';
    exception when invalid_parameter_value then null;
    end;
    begin
        insert into public.places(project_id, parent_id, name) values
            ('00000000-0000-0000-0000-0000000000f2', '00000000-0000-0000-0000-00000000c001', 'Mista');
        raise exception 'genitore di un altro progetto accettato';
    exception when invalid_parameter_value then null;
    end;
    begin
        update public.objects set place_id = '00000000-0000-0000-0000-00000000c003'
        where id = '00000000-0000-0000-0000-0000000000b1';
        raise exception 'luogo di un altro progetto accettato';
    exception when invalid_parameter_value then null;
    end;
end;
$$;

-- Cancellare "Casa": Garage sale di livello, l'oggetto resta in Garage.
delete from public.places where id = '00000000-0000-0000-0000-00000000c001';
do $$
begin
    if (select parent_id from public.places where id = '00000000-0000-0000-0000-00000000c002') is not null then
        raise exception 'il sotto-luogo non è salito di livello';
    end if;
    if (select place_id from public.objects where id = '00000000-0000-0000-0000-0000000000b1') is null then
        raise exception 'oggetto staccato dal suo luogo';
    end if;
end;
$$;
-- Cancellare "Garage": l'oggetto resta senza luogo, non viene cancellato.
delete from public.places where id = '00000000-0000-0000-0000-00000000c002';
do $$
begin
    if not exists (select 1 from public.objects where id = '00000000-0000-0000-0000-0000000000b1' and place_id is null) then
        raise exception 'oggetto perso con il luogo';
    end if;
end;
$$;
select 'places_test: ok' as result;
rollback;
