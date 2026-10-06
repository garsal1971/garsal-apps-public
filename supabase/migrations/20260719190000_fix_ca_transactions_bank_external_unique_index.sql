-- Fix: l'upsert in enable-banking-sync usa ON CONFLICT (bank_connection_id, external_id)
-- senza clausola WHERE, ma l'indice creato in 20260718110000 era parziale
-- (WHERE bank_connection_id IS NOT NULL AND external_id IS NOT NULL) — Postgres non riesce
-- ad abbinare un ON CONFLICT "semplice" a un indice unico parziale, da cui l'errore
-- "there is no unique or exclusion constraint matching the ON CONFLICT specification".
--
-- Un indice unico NON parziale su queste due colonne funziona comunque per le transazioni
-- CSV (bank_connection_id/external_id NULL): Postgres considera ogni riga con NULL come
-- distinta dalle altre nel controllo di unicità, quindi righe CSV multiple restano ammesse.

DROP INDEX IF EXISTS idx_ca_transactions_bank_external;

CREATE UNIQUE INDEX IF NOT EXISTS idx_ca_transactions_bank_external
  ON ca_transactions(bank_connection_id, external_id);
