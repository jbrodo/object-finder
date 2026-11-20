-- Object Finder / Migration 014 — prestiti.
-- Stato del prestito sull'oggetto (sincronizzato e gestito nei conflitti come gli altri campi):
-- a chi è prestato, da quando e, facoltativa, la data di restituzione prevista.
-- Default "non prestato": le app ≤ 1.8 non conoscono le colonne e continuano a funzionare.

alter table public.objects
    add column if not exists loaned_to text not null default '',
    add column if not exists loaned_at timestamptz,
    add column if not exists loan_due date;

alter table public.objects drop constraint if exists objects_loan_consistent;
alter table public.objects add constraint objects_loan_consistent check (
    (loaned_to = '') = (loaned_at is null)
    and (loan_due is null or loaned_to <> '')
    and char_length(loaned_to) <= 120
);

-- Elenco "In prestito" per progetto.
create index if not exists objects_loaned_idx on public.objects(project_id) where loaned_to <> '';
