-- Spuntiamola (sp_*) — periodo e spunte sincronizzati fra dispositivi
-- Prima di questa migration i dati vivevano solo in localStorage: erano
-- per-dispositivo. Ora il DB è la fonte di verità e localStorage resta
-- come cache offline.
-- ---------------------------------------------------------------------------
-- 1. sp_settings — una riga per utente: il periodo e le opzioni
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sp_settings (
  user_id       uuid PRIMARY KEY DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
  goal          text NOT NULL DEFAULT 'Il traguardo',
  emoji         text NOT NULL DEFAULT '🎯',
  start_date    date NOT NULL DEFAULT CURRENT_DATE,
  end_date      date NOT NULL,
  -- true = si contano solo i giorni feriali
  skip_weekend  boolean NOT NULL DEFAULT false,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT sp_settings_period_ok CHECK (end_date >= start_date)
);
ALTER TABLE sp_settings ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS "sp_settings_own" ON sp_settings;
CREATE POLICY "sp_settings_own" ON sp_settings FOR ALL USING (user_id = auth.uid());

-- ---------------------------------------------------------------------------
-- 2. sp_checks — una riga per giorno spuntato
--    L'emoji è quella pescata a caso al momento della spunta: resta legata
--    al giorno, così la griglia si ripopola uguale su ogni dispositivo.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sp_checks (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     uuid NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
  day         date NOT NULL,
  emoji       text NOT NULL DEFAULT '✅',
  created_at  timestamptz NOT NULL DEFAULT now(),
  -- un giorno si spunta una volta sola
  CONSTRAINT uq_sp_checks_user_day UNIQUE (user_id, day)
);
CREATE INDEX IF NOT EXISTS idx_sp_checks_user_day ON sp_checks(user_id, day);
ALTER TABLE sp_checks ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS "sp_checks_own" ON sp_checks;
CREATE POLICY "sp_checks_own" ON sp_checks FOR ALL USING (user_id = auth.uid());

-- ---------------------------------------------------------------------------
-- 3. updated_at automatico su sp_settings
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION sp_settings_touch()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  NEW.updated_at := now();
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_sp_settings_touch ON sp_settings;
CREATE TRIGGER trg_sp_settings_touch
  BEFORE UPDATE ON sp_settings
  FOR EACH ROW EXECUTE FUNCTION sp_settings_touch();

-- ---------------------------------------------------------------------------
-- 4. score_query del launcher: ora c'è qualcosa da contare davvero,
--    cioè i giorni che mancano (da oggi alla fine, non ancora spuntati).
-- ---------------------------------------------------------------------------
UPDATE cm_apps
   SET score_query = $q$
SELECT COALESCE((
  SELECT COUNT(*)
    FROM sp_settings s
    CROSS JOIN LATERAL generate_series(
           GREATEST(s.start_date, CURRENT_DATE)::timestamp,
           s.end_date::timestamp,
           interval '1 day') AS g(d)
   WHERE s.user_id = auth.uid()
     AND (NOT s.skip_weekend OR EXTRACT(dow FROM g.d) NOT IN (0, 6))
     AND NOT EXISTS (SELECT 1 FROM sp_checks c
                      WHERE c.user_id = s.user_id AND c.day = g.d::date)
), 0)::int
$q$
 WHERE title = 'Spuntiamola';
