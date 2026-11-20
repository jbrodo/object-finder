-- Test SQL della quota piante (migration 017): limite per utente, limite globale, niente accesso ai client.
begin;
insert into auth.users(id, email, email_confirmed_at) values
    ('00000000-0000-0000-0000-0000000000a1', 'uno@example.com', now()),
    ('00000000-0000-0000-0000-0000000000b2', 'due@example.com', now());

do $$
begin
    if public.plant_quota_take('00000000-0000-0000-0000-0000000000a1', 2, 3) <> 1 then raise exception 'prima: attese 1 rimanenti'; end if;
    if public.plant_quota_take('00000000-0000-0000-0000-0000000000a1', 2, 3) <> 0 then raise exception 'seconda: attese 0 rimanenti'; end if;
    if public.plant_quota_take('00000000-0000-0000-0000-0000000000a1', 2, 3) <> -1 then raise exception 'limite utente non applicato'; end if;
    if public.plant_quota_take('00000000-0000-0000-0000-0000000000b2', 2, 3) <> 1 then raise exception 'altro utente bloccato'; end if;
    -- Totale 3 = limite globale: anche chi ha ancora quota personale si ferma.
    if public.plant_quota_take('00000000-0000-0000-0000-0000000000b2', 2, 3) <> -1 then raise exception 'limite globale non applicato'; end if;
    if (select sum(count) from public.plant_id_usage) <> 3 then raise exception 'conteggio errato'; end if;
end;
$$;

-- Un client autenticato non può chiamare la funzione né leggere la tabella.
set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-0000000000a1', true);
do $$
begin
    begin
        perform public.plant_quota_take('00000000-0000-0000-0000-0000000000a1', 100, 100);
        raise exception 'client autorizzato a consumare quota';
    exception when insufficient_privilege then null;
    end;
    begin
        perform 1 from public.plant_id_usage;
        raise exception 'client autorizzato a leggere la quota';
    exception when insufficient_privilege then null;
    end;
end;
$$;
reset role;
select 'plant_quota_test: ok' as result;
rollback;
