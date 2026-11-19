-- Eseguire dopo 001. Bucket privato per fotografie.
insert into storage.buckets (id, name, public)
values ('object-photos', 'object-photos', false)
on conflict (id) do nothing;

-- I percorsi file devono essere organizzati come: <project_uuid>/<object_uuid>/<filename>
create policy "project members can read object photos"
on storage.objects for select to authenticated
using (
  bucket_id = 'object-photos'
  and exists (
    select 1 from public.objects o
    where o.id::text = (storage.foldername(name))[2]
      and o.project_id::text = (storage.foldername(name))[1]
      and public.is_project_member(o.project_id)
  )
);

create policy "editors can upload object photos"
on storage.objects for insert to authenticated
with check (
  bucket_id = 'object-photos'
  and exists (
    select 1 from public.objects o
    where o.id::text = (storage.foldername(name))[2]
      and o.project_id::text = (storage.foldername(name))[1]
      and public.has_project_role(o.project_id, array['owner','admin','editor']::public.project_role[])
  )
);
