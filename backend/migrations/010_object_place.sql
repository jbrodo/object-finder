-- "Dove": descrizione libera del punto in cui si trova l'oggetto ("garage, scaffale alto").
-- Al chiuso il GPS sbaglia di 5-20 m: una frase scritta da chi ha riposto l'oggetto è
-- spesso l'informazione più utile per ritrovarlo. Default '' così le app ≤ 1.1, che non
-- conoscono la colonna, continuano a inserire e aggiornare oggetti senza errori.

alter table public.objects
    add column if not exists place text not null default '';

alter table public.objects
    drop constraint if exists objects_place_length;

alter table public.objects
    add constraint objects_place_length check (char_length(place) <= 200);
