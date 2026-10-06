-- Reddito (finanza.html → 💶 Reddito) — quanto è entrato, anno per anno.
--
-- Una riga per anno E per tipo, non una riga per anno con quattro colonne fisse: i tipi di
-- reddito cambiano nel tempo (oggi lavoro, pensione, affitto e dichiarazione; domani gli
-- interessi o altro) e con le colonne ogni tipo nuovo sarebbe una modifica alla struttura
-- della tabella, cioè una migration da concordare. Così è una voce in più in INCOME_KINDS
-- dentro finanza.html e basta.
--
-- Niente colonna persona: la tabella è già per utente attraverso la RLS, e qui si registra il
-- reddito di chi la compila. Niente mese o data: è un dato annuale, e una colonna data
-- costringerebbe a inventare un giorno per un importo che il giorno non ce l'ha.

CREATE TABLE IF NOT EXISTS fnz_income (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id    uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  year       integer NOT NULL CHECK (year BETWEEN 1900 AND 2200),
  kind       text NOT NULL,
  -- Importo lordo percepito nell'anno per quel tipo. Sempre >= 0: qui non ci sono uscite,
  -- quelle stanno nelle app delle spese.
  amount     numeric(14,2) NOT NULL DEFAULT 0,
  notes      text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

-- Una sola riga per anno e tipo. La pagina scrive in upsert su questa chiave: senza il vincolo
-- ricompilare una casella aggiungerebbe una seconda riga invece di riscrivere la prima, e il
-- totale dell'anno raddoppierebbe in silenzio.
CREATE UNIQUE INDEX IF NOT EXISTS uq_fnz_income_user_year_kind
  ON fnz_income(user_id, year, kind);
CREATE INDEX IF NOT EXISTS idx_fnz_income_user_year
  ON fnz_income(user_id, year DESC);

ALTER TABLE fnz_income ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS fnz_income_own ON fnz_income;
CREATE POLICY fnz_income_own ON fnz_income FOR ALL
  USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());

COMMENT ON TABLE fnz_income IS
  'Reddito annuale per tipo (finanza.html → 💶 Reddito). Una riga per anno e tipo; la pagina scrive in upsert su (user_id, year, kind).';
COMMENT ON COLUMN fnz_income.kind IS
  'Tipo di reddito: lavoro, pensione, affitto, dichiarazione. L''elenco vive in INCOME_KINDS (finanza.html): aggiungerne uno non richiede nessuna migration.';
