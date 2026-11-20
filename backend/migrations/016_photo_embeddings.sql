-- Object Finder / Migration 016 — impronte del riconoscimento condivise.
-- Ogni foto ha un'impronta numerica (embedding) calcolata sul telefono; salvarla sul server evita
-- che un telefono nuovo (o un altro membro) scarichi tutte le foto per ricalcolarla.
-- Le foto sono immutabili, quindi anche le impronte: niente update, cancellazione con la foto.

create table if not exists public.photo_embeddings (
    photo_id uuid not null references public.object_photos(id) on delete cascade,
    -- Modello che l'ha prodotta (es. "mobilenet_v3_large@1"): cambiando modello si ricalcola.
    model text not null check (char_length(model) between 1 and 64),
    -- Vettore float32 little-endian in base64 (max 4096 dimensioni).
    vector text not null check (
        octet_length(decode(vector, 'base64')) between 4 and 16384
        and octet_length(decode(vector, 'base64')) % 4 = 0
    ),
    created_by uuid references auth.users(id) on delete set null default auth.uid(),
    created_at timestamptz not null default now(),
    primary key (photo_id, model)
);

alter table public.photo_embeddings enable row level security;

-- Lettura: chi vede la foto. Scrittura: chi può aggiungere foto (editor+), per non permettere a
-- un viewer di "avvelenare" il riconoscimento con impronte false.
drop policy if exists photo_embeddings_select on public.photo_embeddings;
create policy photo_embeddings_select on public.photo_embeddings for select
using (exists (
    select 1 from public.object_photos p join public.objects o on o.id = p.object_id
    where p.id = photo_id and public.is_project_member(o.project_id)
));

drop policy if exists photo_embeddings_insert on public.photo_embeddings;
create policy photo_embeddings_insert on public.photo_embeddings for insert
with check (exists (
    select 1 from public.object_photos p join public.objects o on o.id = p.object_id
    where p.id = photo_id
      and public.has_project_role(o.project_id, array['owner','admin','editor']::public.project_role[])
));

drop policy if exists photo_embeddings_delete on public.photo_embeddings;
create policy photo_embeddings_delete on public.photo_embeddings for delete
using (exists (
    select 1 from public.object_photos p join public.objects o on o.id = p.object_id
    where p.id = photo_id
      and public.has_project_role(o.project_id, array['owner','admin','editor']::public.project_role[])
));

grant select, insert, delete on public.photo_embeddings to authenticated;
