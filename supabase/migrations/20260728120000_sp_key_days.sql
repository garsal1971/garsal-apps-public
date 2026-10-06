-- Spuntiamola — giornate chiave
-- Giorni "che contano" dentro il periodo (una scadenza, un compleanno, la
-- partenza): si segnano dalle impostazioni e alla spunta fanno scattare
-- l'esplosione di stelle invece del solito toast.
CREATE TABLE IF NOT EXISTS sp_key_days (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     uuid NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
  day         date NOT NULL,
  -- etichetta libera ("Partenza!"), mostrata nel messaggio della spunta
  label       text NOT NULL DEFAULT '',
  created_at  timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_sp_key_days_user_day UNIQUE (user_id, day)
);
CREATE INDEX IF NOT EXISTS idx_sp_key_days_user_day ON sp_key_days(user_id, day);
ALTER TABLE sp_key_days ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS "sp_key_days_own" ON sp_key_days;
CREATE POLICY "sp_key_days_own" ON sp_key_days FOR ALL USING (user_id = auth.uid());
