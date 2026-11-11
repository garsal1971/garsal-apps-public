-- Verifica finalizzata: il voto preso, come si giudica la programmazione dello
-- studio (triste / indifferente / contenta) e un commento. NULL = non ancora finalizzata.
-- Li scrive il bottone 🏁 di verifiche-ada.html. ⚠️ Nessun dato qui.

ALTER TABLE sc_verifiche
  ADD COLUMN IF NOT EXISTS voto            numeric(4,2) CHECK (voto IS NULL OR (voto >= 1 AND voto <= 10)),
  ADD COLUMN IF NOT EXISTS giudizio        text CHECK (giudizio IS NULL OR giudizio IN ('triste','indifferente','contenta')),
  ADD COLUMN IF NOT EXISTS commento        text,
  ADD COLUMN IF NOT EXISTS finalizzata_il  date;
