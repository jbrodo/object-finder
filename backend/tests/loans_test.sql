-- Test SQL dei prestiti (migration 014): vincoli di coerenza. Nessun dato resta (rollback).
begin;
insert into auth.users(id, email, email_confirmed_at) values ('00000000-0000-0000-0000-0000000000a1', 'owner@example.com', now());
insert into public.projects(id, name, owner_id) values ('00000000-0000-0000-0000-0000000000f1', 'Casa', '00000000-0000-0000-0000-0000000000a1');
insert into public.objects(id, project_id, name) values ('00000000-0000-0000-0000-0000000000b1', '00000000-0000-0000-0000-0000000000f1', 'Trapano');

-- Prestito valido, poi restituzione.
update public.objects set loaned_to = 'Marco', loaned_at = now(), loan_due = current_date + 7
where id = '00000000-0000-0000-0000-0000000000b1';
update public.objects set loaned_to = '', loaned_at = null, loan_due = null
where id = '00000000-0000-0000-0000-0000000000b1';

do $$
begin
    begin
        update public.objects set loaned_to = 'Marco' where id = '00000000-0000-0000-0000-0000000000b1';
        raise exception 'prestito senza data accettato';
    exception when check_violation then null;
    end;
    begin
        update public.objects set loan_due = current_date where id = '00000000-0000-0000-0000-0000000000b1';
        raise exception 'scadenza senza prestito accettata';
    exception when check_violation then null;
    end;
end;
$$;
select 'loans_test: ok' as result;
rollback;
