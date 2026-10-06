-- Rimuove total_points da dc_decisions.
-- I totali vengono ora calcolati in runtime sommando dc_logs.points_earned.
ALTER TABLE dc_decisions DROP COLUMN IF EXISTS total_points;
