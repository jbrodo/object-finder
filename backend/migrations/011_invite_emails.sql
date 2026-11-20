-- Object Finder / Migration 011 — email di invito.
-- La Edge Function `send-invite` manda l'email a chi è stato invitato senza account e registra
-- qui l'invio, per limitare i reinvii (anti-spam) senza dare ai client un permesso di scrittura.

alter table public.project_invites
    add column if not exists last_emailed_at timestamptz,
    add column if not exists email_count integer not null default 0;

-- Invii recenti per progetto (limite giornaliero calcolato dalla funzione).
create index if not exists project_invites_emailed_idx
    on public.project_invites(project_id, last_emailed_at)
    where last_emailed_at is not null;
