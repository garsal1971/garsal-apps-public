-- Permette di marcare una categoria principale come esclusa dai totali di dashboard
-- (giroconti, pagamenti carta di credito, ricariche: movimenti reali ma che non devono
-- contare come spesa/entrata). Le sotto-categorie non hanno un flag proprio: ereditano
-- l'esclusione dalla categoria padre, come già avviene per il colore.

ALTER TABLE ca_categories ADD COLUMN IF NOT EXISTS excluded_from_totals boolean NOT NULL DEFAULT false;
