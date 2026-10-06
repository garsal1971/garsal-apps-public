-- Aggiunge il tipo di obiettivo a ps_objectives.
-- 'perdere'   → obiettivo classico con milestone progressive (default, retro-compatibile)
-- 'mantenere' → obiettivo "mantieni peso": target piatto su un periodo definito da settimane
ALTER TABLE ps_objectives
  ADD COLUMN IF NOT EXISTS objective_type text NOT NULL DEFAULT 'perdere';
