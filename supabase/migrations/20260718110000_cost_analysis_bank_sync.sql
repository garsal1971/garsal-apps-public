-- Analisi Costi — fase 1 del sync bancario via Enable Banking (PSD2 restricted mode):
-- schema per conti collegati, mapping merchant category code -> categoria, e i nuovi campi
-- su ca_transactions per distinguere "chi ha speso" (automatico dal conto di origine) da
-- "per chi è la spesa" (person_id esistente, manuale). Le chiamate API reali a Enable Banking
-- arrivano in una fase successiva, quando saranno disponibili le credenziali dell'app.

-- ca_bank_connections: un conto collegato via Enable Banking
CREATE TABLE IF NOT EXISTS ca_bank_connections (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid REFERENCES auth.users(id) ON DELETE CASCADE,
  provider text NOT NULL DEFAULT 'enable_banking',
  aspsp_name text NOT NULL,              -- es. 'Revolut', 'N26'
  display_name text,                     -- nome libero per riconoscere il conto in UI
  owner_person_id uuid REFERENCES ca_people(id) ON DELETE SET NULL, -- di chi è il conto -> "chi ha speso"
  account_id text,                       -- id conto lato Enable Banking (nullo finché non collegato davvero)
  consent_id text,
  consent_expires_at timestamptz,
  status text NOT NULL DEFAULT 'active', -- 'active' | 'expired' | 'revoked'
  created_at timestamptz DEFAULT now()
);

-- ca_mcc_category_map: merchant category code -> categoria (primo livello di categorizzazione, deterministico)
CREATE TABLE IF NOT EXISTS ca_mcc_category_map (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid REFERENCES auth.users(id) ON DELETE CASCADE,
  mcc text NOT NULL,
  category_id uuid REFERENCES ca_categories(id) ON DELETE CASCADE,
  created_at timestamptz DEFAULT now(),
  UNIQUE(user_id, mcc)
);

-- ca_sync_log: storico sincronizzazioni per debug/audit
CREATE TABLE IF NOT EXISTS ca_sync_log (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid REFERENCES auth.users(id) ON DELETE CASCADE,
  bank_connection_id uuid REFERENCES ca_bank_connections(id) ON DELETE CASCADE,
  started_at timestamptz DEFAULT now(),
  finished_at timestamptz,
  status text,                           -- 'success' | 'error'
  imported_count int DEFAULT 0,
  error_message text
);

-- ca_transactions: nuovi campi per il sync bancario
ALTER TABLE ca_transactions ADD COLUMN IF NOT EXISTS spender_person_id uuid REFERENCES ca_people(id) ON DELETE SET NULL;
ALTER TABLE ca_transactions ADD COLUMN IF NOT EXISTS bank_connection_id uuid REFERENCES ca_bank_connections(id) ON DELETE SET NULL;
ALTER TABLE ca_transactions ADD COLUMN IF NOT EXISTS external_id text;
ALTER TABLE ca_transactions ADD COLUMN IF NOT EXISTS mcc text;
ALTER TABLE ca_transactions ADD COLUMN IF NOT EXISTS import_source text NOT NULL DEFAULT 'csv'; -- 'csv' | 'bank_sync'

-- Idempotenza del sync: la stessa transazione bancaria non va importata due volte
CREATE UNIQUE INDEX IF NOT EXISTS idx_ca_transactions_bank_external
  ON ca_transactions(bank_connection_id, external_id)
  WHERE bank_connection_id IS NOT NULL AND external_id IS NOT NULL;

ALTER TABLE ca_bank_connections ENABLE ROW LEVEL SECURITY;
ALTER TABLE ca_mcc_category_map ENABLE ROW LEVEL SECURITY;
ALTER TABLE ca_sync_log ENABLE ROW LEVEL SECURITY;

DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename = 'ca_bank_connections' AND policyname = 'ca_bank_connections_owner') THEN
    CREATE POLICY ca_bank_connections_owner ON ca_bank_connections FOR ALL USING (user_id = auth.uid());
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename = 'ca_mcc_category_map' AND policyname = 'ca_mcc_category_map_owner') THEN
    CREATE POLICY ca_mcc_category_map_owner ON ca_mcc_category_map FOR ALL USING (user_id = auth.uid());
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename = 'ca_sync_log' AND policyname = 'ca_sync_log_owner') THEN
    CREATE POLICY ca_sync_log_owner ON ca_sync_log FOR ALL USING (user_id = auth.uid());
  END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_ca_bank_connections_user ON ca_bank_connections(user_id);
CREATE INDEX IF NOT EXISTS idx_ca_mcc_category_map_user ON ca_mcc_category_map(user_id);
CREATE INDEX IF NOT EXISTS idx_ca_sync_log_connection ON ca_sync_log(bank_connection_id);
CREATE INDEX IF NOT EXISTS idx_ca_transactions_spender ON ca_transactions(spender_person_id);
CREATE INDEX IF NOT EXISTS idx_ca_transactions_bank_connection ON ca_transactions(bank_connection_id);
