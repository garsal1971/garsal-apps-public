-- Nuovo attributo "visibile in app" per ca_categories: se disattivato, la categoria non compare
-- nella scelta categoria interattiva dello Smart Block (revolut-auto-categorize). Vale sia per
-- le categorie principali che per le sotto-categorie; disattivarlo su una principale nasconde
-- comunque anche tutte le sue figlie (calcolato lato function, non serve propagare il valore).
alter table ca_categories add column if not exists visible_in_app boolean not null default true;
