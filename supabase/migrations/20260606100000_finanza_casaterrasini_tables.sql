-- Casa Terrasini Tables (Duplicated from Casa Rosa with _terr suffix)

-- Categories
CREATE TABLE IF NOT EXISTS cntrs_categories_terr (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid REFERENCES auth.users(id) ON DELETE CASCADE,
  name text NOT NULL,
  created_at timestamptz DEFAULT now(),
  UNIQUE(user_id, name)
);
ALTER TABLE cntrs_categories_terr ENABLE ROW LEVEL SECURITY;
CREATE POLICY "cntrs_categories_terr_own" ON cntrs_categories_terr FOR ALL USING (user_id = auth.uid());

-- Transactions
CREATE TABLE IF NOT EXISTS cntrs_transactions_terr (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid REFERENCES auth.users(id) ON DELETE CASCADE,
  anno integer NOT NULL,
  data date NOT NULL,
  descrizione text NOT NULL,
  importo_voce numeric(12,2) DEFAULT 0,
  category_id uuid REFERENCES cntrs_categories_terr(id) ON DELETE SET NULL,
  note text DEFAULT '',
  created_at timestamptz DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_cntrs_trans_terr_user ON cntrs_transactions_terr(user_id);
ALTER TABLE cntrs_transactions_terr ENABLE ROW LEVEL SECURITY;
CREATE POLICY "cntrs_transactions_terr_own" ON cntrs_transactions_terr FOR ALL USING (user_id = auth.uid());

-- Legacy Data
CREATE TABLE IF NOT EXISTS cntrs_legacy_data_terr (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid REFERENCES auth.users(id) ON DELETE CASCADE,
  foglio text NOT NULL,
  dati jsonb NOT NULL,
  created_at timestamptz DEFAULT now()
);
ALTER TABLE cntrs_legacy_data_terr ENABLE ROW LEVEL SECURITY;
CREATE POLICY "cntrs_legacy_data_terr_own" ON cntrs_legacy_data_terr FOR ALL USING (user_id = auth.uid());

-- Saldi
CREATE TABLE IF NOT EXISTS cntrs_saldi_terr (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid REFERENCES auth.users(id) ON DELETE CASCADE,
  data date NOT NULL,
  saldo_contabile numeric(12,2) DEFAULT 0,
  saldo_disponibile numeric(12,2) DEFAULT 0,
  note text DEFAULT '',
  created_at timestamptz DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_cntrs_saldi_terr_user ON cntrs_saldi_terr(user_id);
ALTER TABLE cntrs_saldi_terr ENABLE ROW LEVEL SECURITY;
CREATE POLICY "cntrs_saldi_terr_own" ON cntrs_saldi_terr FOR ALL USING (user_id = auth.uid());

-- Seed App in cm_apps
INSERT INTO cm_apps (title, description, score_query, color, active, html_file, riservato)
SELECT 'Casa Terrasini', 'Situazione cassa e pagamenti Casa Terrasini', 'SELECT COUNT(*)::int FROM cntrs_transactions_terr WHERE user_id = auth.uid()', '#16a34a', true, 'casaterrasini.html', true
WHERE NOT EXISTS (SELECT 1 FROM cm_apps WHERE title = 'Casa Terrasini');
