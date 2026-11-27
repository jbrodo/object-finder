-- Confirm only the exact set of values the user reviewed, under the same lock used by row edits.
create function public.confirm_water_import_reviewed(p_id uuid, p_rows jsonb)
returns integer language plpgsql security invoker set search_path = '' as $$
declare v_count integer;
begin
    if p_rows is null or jsonb_typeof(p_rows) <> 'array' then
        raise exception 'invalid review' using errcode = '23514';
    end if;
    select 1 into v_count from public.water_imports where id = p_id and confirmed_at is null for update;
    if not found then raise exception 'import unavailable' using errcode = '42501'; end if;
    select count(*) into v_count from public.water_import_rows where import_id = p_id;
    if v_count = 0 or v_count <> jsonb_array_length(p_rows) or exists (
        select id, unit_id, kind, period_start, period_end, amount from public.water_import_rows where import_id = p_id
        except
        select id, unit_id, kind, period_start, period_end, amount from jsonb_to_recordset(p_rows)
          as reviewed(id uuid, unit_id uuid, kind text, period_start date, period_end date, amount numeric)
    ) or exists (
        select id, unit_id, kind, period_start, period_end, amount from jsonb_to_recordset(p_rows)
          as reviewed(id uuid, unit_id uuid, kind text, period_start date, period_end date, amount numeric)
        except
        select id, unit_id, kind, period_start, period_end, amount from public.water_import_rows where import_id = p_id
    ) then
        raise exception 'import changed since review' using errcode = '40001';
    end if;
    return public.confirm_water_import(p_id);
end;
$$;
revoke all on function public.confirm_water_import_reviewed(uuid, jsonb) from public, anon;
grant execute on function public.confirm_water_import_reviewed(uuid, jsonb) to authenticated;
