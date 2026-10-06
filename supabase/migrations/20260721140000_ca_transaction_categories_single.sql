-- Porta ca_transaction_categories da modello multi-tag a categoria singola per
-- transazione: per ogni transazione con più righe tiene solo quella con la
-- priorità più alta tra le fonti (manual/trip prima, poi le altre) e la
-- rimozione delle righe in eccesso, poi aggiunge un vincolo di unicità che
-- impedisce da ora in poi di assegnare più di una categoria alla stessa
-- transazione.
WITH ranked AS (
  SELECT transaction_id, category_id,
         ROW_NUMBER() OVER (
           PARTITION BY transaction_id
           ORDER BY CASE source WHEN 'manual' THEN 0 WHEN 'trip' THEN 1 ELSE 2 END, category_id
         ) AS rn
  FROM ca_transaction_categories
)
DELETE FROM ca_transaction_categories t
USING ranked r
WHERE t.transaction_id = r.transaction_id AND t.category_id = r.category_id AND r.rn > 1;

ALTER TABLE ca_transaction_categories
  ADD CONSTRAINT ca_transaction_categories_transaction_unique UNIQUE (transaction_id);
