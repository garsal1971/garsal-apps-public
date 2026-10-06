-- Previdenza (finanza.html → 💶 Reddito → Previdenza) — storico contributivo dall'estratto
-- conto INPS.
--
-- Una riga per periodo e tipo di contribuzione, così com'è nell'estratto conto: niente vincolo
-- di unicità su (periodo, tipo), perché il documento stesso ne ha di legittimamente ripetuti —
-- es. tre righe "Mater/pater/congedi(Int)" nello stesso 2010 con importi diversi. Lo storico si
-- carica una volta con una migration dati (come fnz_income_dati_storici.sql) e la pagina
-- permette di aggiungere a mano le righe dei periodi futuri, che arrivano un anno alla volta.

CREATE TABLE IF NOT EXISTS fnz_pension_contributions (
  id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id           uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  period_start      date NOT NULL,
  period_end        date NOT NULL,
  contribution_type text NOT NULL,
  weeks_valid       numeric(6,3),
  income_amount     numeric(14,2),
  employer          text NOT NULL DEFAULT '',
  note              text NOT NULL DEFAULT '',
  created_at        timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_fnz_pension_contributions_user_period
  ON fnz_pension_contributions(user_id, period_start DESC);

ALTER TABLE fnz_pension_contributions ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS fnz_pension_contributions_own ON fnz_pension_contributions;
CREATE POLICY fnz_pension_contributions_own ON fnz_pension_contributions FOR ALL
  USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());

COMMENT ON TABLE fnz_pension_contributions IS
  'Periodi contributivi dell''estratto conto previdenziale INPS (finanza.html → 💶 Reddito → Previdenza). Caricata una volta da migration dati; le righe dei periodi futuri si aggiungono a mano dalla pagina.';
COMMENT ON COLUMN fnz_pension_contributions.weeks_valid IS
  'Settimane utili al diritto e al calcolo, come in colonna "Contributi utili pensione" dell''estratto conto (può essere frazionario per il part-time, es. 22,000).';
