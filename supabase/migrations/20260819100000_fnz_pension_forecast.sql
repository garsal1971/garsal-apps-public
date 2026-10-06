-- Previdenza (finanza.html → 💶 Reddito → Previdenza) — previsione pensionistica INPS.
--
-- Una riga per scenario (vecchiaia, anticipata), sovrascritta ad ogni nuova simulazione fatta
-- sul sito INPS: non è uno storico, è l'ultimo scatto — come lo snapshot del patrimonio, ma qui
-- non c'è un job che lo rifà da solo, lo si aggiorna a mano quando si rifà la simulazione.

CREATE TABLE IF NOT EXISTS fnz_pension_forecast (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id          uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  scenario         text NOT NULL CHECK (scenario IN ('vecchiaia', 'anticipata')),
  pension_date     date,
  gross_amount     numeric(14,2),
  last_salary      numeric(14,2),
  replacement_rate numeric(5,2),
  issued_at        date,
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now()
);

-- Una sola riga per scenario. La pagina scrive in upsert su questa chiave: rifare la
-- simulazione riscrive la riga invece di aggiungerne una seconda.
CREATE UNIQUE INDEX IF NOT EXISTS uq_fnz_pension_forecast_user_scenario
  ON fnz_pension_forecast(user_id, scenario);

ALTER TABLE fnz_pension_forecast ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS fnz_pension_forecast_own ON fnz_pension_forecast;
CREATE POLICY fnz_pension_forecast_own ON fnz_pension_forecast FOR ALL
  USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());

COMMENT ON TABLE fnz_pension_forecast IS
  'Previsione pensionistica INPS ("La mia Pensione"), un rigo per scenario (finanza.html → 💶 Reddito → Previdenza). La pagina scrive in upsert su (user_id, scenario): rifare la simulazione sovrascrive.';
COMMENT ON COLUMN fnz_pension_forecast.replacement_rate IS
  'Tasso di sostituzione lordo, in percentuale (es. 75.46).';
