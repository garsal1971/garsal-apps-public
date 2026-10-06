-- Danaro di Rosa (finanza.html → 🌹 Danaro di Rosa) — movimenti del conto di Rosa.
--
-- Il conto (Conto Arancio) si collega come tutti gli altri da Configurazione → 🏦 Banche e
-- Conti e si spunta come «🌹 Danaro di Rosa»: da lì la pagina legge i movimenti con
-- enable-banking-transactions e li archivia qui.
--
-- Niente categorie, di proposito: qui non si analizza la spesa come in Spese Famiglia o Spese
-- Ada, si tiene il registro delle operazioni del conto e si aggiorna il saldo della voce in
-- fnz_other_assets. Una colonna categoria vuota che nessuno riempie è solo un invito a
-- reimplementare la categorizzazione una quarta volta.
--
-- Tabella a sé e non le ca_*/ada_*: i soldi di Rosa sono esclusi dal patrimonio personale
-- (fnz_other_assets.asset_type = 'danaro_rosa') e non devono entrare in nessun totale di
-- famiglia. La separazione per tabella è l'unica che non può sbagliare per dimenticanza.

CREATE TABLE IF NOT EXISTS fnz_rosa_transactions (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id            uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  bank_connection_id uuid REFERENCES cm_bank_connections(id) ON DELETE SET NULL,
  external_id        text,
  -- Importo con segno come nel resto delle app: uscita < 0, entrata > 0.
  date               date NOT NULL,
  amount             numeric(18,2) NOT NULL,
  currency           text,
  description        text NOT NULL DEFAULT '',
  counterparty       text NOT NULL DEFAULT '',
  counterparty_iban  text,
  bank_code          text,
  created_at         timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_fnz_rosa_transactions_user_date
  ON fnz_rosa_transactions(user_id, date DESC);

-- Idempotenza della lettura: la stessa riga della banca non entra due volte. Come per
-- ada_transactions l'indice non è parziale — Postgres non abbina un ON CONFLICT semplice a un
-- indice parziale, e le righe con external_id NULL restano comunque distinte fra loro.
CREATE UNIQUE INDEX IF NOT EXISTS uq_fnz_rosa_transactions_bank_external
  ON fnz_rosa_transactions(bank_connection_id, external_id);

ALTER TABLE fnz_rosa_transactions ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS fnz_rosa_transactions_own ON fnz_rosa_transactions;
CREATE POLICY fnz_rosa_transactions_own ON fnz_rosa_transactions FOR ALL
  USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());

-- ── Un settimo uso per i conti collegati ────────────────────────────
-- uses è un array: l'uso si aggiunge a quelli che il conto ha già, senza toglierne nessuno.
ALTER TABLE cm_bank_connections DROP CONSTRAINT IF EXISTS cm_bank_connections_uses_check;
ALTER TABLE cm_bank_connections
  ADD CONSTRAINT cm_bank_connections_uses_check
  CHECK (uses <@ ARRAY['cost_analysis', 'fondo', 'conto_risparmio', 'contribuzione', 'spese_ada', 'casa_rosa', 'danaro_rosa']::text[]);

COMMENT ON COLUMN cm_bank_connections.uses IS
  'A cosa serve il conto: cost_analysis (Spese Famiglia), fondo, conto_risparmio, contribuzione, spese_ada, casa_rosa, danaro_rosa. Vuoto = conto scoperto e non ancora battezzato.';
