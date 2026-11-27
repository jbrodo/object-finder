-- Preserve unreviewed source values. They do not assign units or dates automatically.
alter table public.water_import_rows
    add column source_unit_label text not null default '' check (char_length(source_unit_label) <= 200),
    add column source_period_label text not null default '' check (char_length(source_period_label) <= 200),
    add column source_amount text not null default '' check (char_length(source_amount) <= 200),
    add column source_warnings jsonb not null default '[]'::jsonb
        check (jsonb_typeof(source_warnings) = 'array' and jsonb_array_length(source_warnings) <= 50);

-- Source evidence is immutable; reviewers may edit only the normalized candidate fields.
revoke update on public.water_import_rows from authenticated;
grant update (unit_id, kind, period_start, period_end, amount) on public.water_import_rows to authenticated;

-- SECURITY INVOKER keeps the existing RLS and row-ownership checks. One call is one transaction.
create function public.stage_water_candidates(
    p_import_id uuid, p_project_id uuid, p_building_id uuid, p_source_name text,
    p_source_type text, p_rows jsonb
) returns uuid language plpgsql security invoker set search_path = '' as $$
begin
    if p_import_id is null or p_rows is null or jsonb_typeof(p_rows) <> 'array' then
        raise exception 'invalid candidates' using errcode = '23514';
    end if;
    if jsonb_array_length(p_rows) not between 1 and 1000 then
        raise exception 'expected 1 to 1000 candidates' using errcode = '23514';
    end if;
    insert into public.water_imports(id, project_id, building_id, source_name, source_type)
    values (p_import_id, p_project_id, p_building_id, p_source_name, p_source_type);
    insert into public.water_import_rows(import_id, project_id, building_id, source_row,
        source_unit_label, source_period_label, source_amount, source_warnings, kind, amount)
    select p_import_id, p_project_id, p_building_id, row.source_row,
        coalesce(row.unit_label, ''), coalesce(row.period_label, ''),
        coalesce(row.raw_amount, row.amount::text, ''), coalesce(row.warnings, '[]'::jsonb),
        row.kind, row.amount
    from jsonb_to_recordset(p_rows) as row(source_row text, unit_label text, period_label text,
        raw_amount text, warnings jsonb, kind text, amount numeric);
    return p_import_id;
end;
$$;
revoke all on function public.stage_water_candidates(uuid, uuid, uuid, text, text, jsonb) from public, anon;
grant execute on function public.stage_water_candidates(uuid, uuid, uuid, text, text, jsonb) to authenticated;
