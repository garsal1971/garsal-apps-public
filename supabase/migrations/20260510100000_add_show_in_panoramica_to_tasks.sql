-- Aggiunge il flag show_in_panoramica a ts_tasks.
-- Se false, il task non viene mostrato nella vista Panoramica (dashboard).
-- Default TRUE per tutti i task esistenti e nuovi.

ALTER TABLE ts_tasks
    ADD COLUMN IF NOT EXISTS show_in_panoramica BOOLEAN DEFAULT TRUE;

UPDATE ts_tasks
    SET show_in_panoramica = TRUE
    WHERE show_in_panoramica IS NULL;
