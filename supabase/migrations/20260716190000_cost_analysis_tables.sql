-- Analisi Costi: import transazioni Revolut + categorizzazione automatica
-- (regole parola-chiave + apprendimento da correzioni manuali, sotto-categorie,
-- tag multipli per transazione, distinzione per persona)

-- ca_categories: tassonomia categorie di spesa, dedicata (non cm_categories)
-- con gerarchia a un livello via parent_id
CREATE TABLE IF NOT EXISTS ca_categories (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid REFERENCES auth.users(id) ON DELETE CASCADE,
  parent_id uuid REFERENCES ca_categories(id) ON DELETE CASCADE,
  name text NOT NULL,
  icon text,
  color text,
  created_at timestamptz DEFAULT now()
);

-- ca_people: persone tra cui distinguere le transazioni
CREATE TABLE IF NOT EXISTS ca_people (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid REFERENCES auth.users(id) ON DELETE CASCADE,
  name text NOT NULL,
  color text,
  created_at timestamptz DEFAULT now()
);

-- ca_rules: regole parola-chiave -> categoria (una transazione può matchare più regole -> più tag)
CREATE TABLE IF NOT EXISTS ca_rules (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid REFERENCES auth.users(id) ON DELETE CASCADE,
  category_id uuid REFERENCES ca_categories(id) ON DELETE CASCADE,
  pattern text NOT NULL,
  priority int DEFAULT 0,
  created_at timestamptz DEFAULT now()
);

-- ca_person_rules: regole parola-chiave -> persona (prima che matcha vince)
CREATE TABLE IF NOT EXISTS ca_person_rules (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid REFERENCES auth.users(id) ON DELETE CASCADE,
  person_id uuid REFERENCES ca_people(id) ON DELETE CASCADE,
  pattern text NOT NULL,
  priority int DEFAULT 0,
  created_at timestamptz DEFAULT now()
);

-- ca_merchant_map: apprendimento da correzioni manuali (merchant normalizzato -> set di categorie)
CREATE TABLE IF NOT EXISTS ca_merchant_map (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid REFERENCES auth.users(id) ON DELETE CASCADE,
  merchant_key text NOT NULL,
  updated_at timestamptz DEFAULT now(),
  UNIQUE(user_id, merchant_key)
);

CREATE TABLE IF NOT EXISTS ca_merchant_map_categories (
  merchant_map_id uuid REFERENCES ca_merchant_map(id) ON DELETE CASCADE,
  category_id uuid REFERENCES ca_categories(id) ON DELETE CASCADE,
  PRIMARY KEY (merchant_map_id, category_id)
);

-- ca_transactions: transazioni importate da Revolut
CREATE TABLE IF NOT EXISTS ca_transactions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid REFERENCES auth.users(id) ON DELETE CASCADE,
  date date NOT NULL,
  amount numeric NOT NULL,
  currency text,
  description text,
  type text,
  person_id uuid REFERENCES ca_people(id) ON DELETE SET NULL,
  person_source text DEFAULT 'unassigned',
  raw jsonb,
  created_at timestamptz DEFAULT now()
);

-- ca_transaction_categories: tag multipli categoria per transazione
CREATE TABLE IF NOT EXISTS ca_transaction_categories (
  transaction_id uuid REFERENCES ca_transactions(id) ON DELETE CASCADE,
  category_id uuid REFERENCES ca_categories(id) ON DELETE CASCADE,
  source text DEFAULT 'manual',
  PRIMARY KEY (transaction_id, category_id)
);

ALTER TABLE ca_categories ENABLE ROW LEVEL SECURITY;
ALTER TABLE ca_people ENABLE ROW LEVEL SECURITY;
ALTER TABLE ca_rules ENABLE ROW LEVEL SECURITY;
ALTER TABLE ca_person_rules ENABLE ROW LEVEL SECURITY;
ALTER TABLE ca_merchant_map ENABLE ROW LEVEL SECURITY;
ALTER TABLE ca_merchant_map_categories ENABLE ROW LEVEL SECURITY;
ALTER TABLE ca_transactions ENABLE ROW LEVEL SECURITY;
ALTER TABLE ca_transaction_categories ENABLE ROW LEVEL SECURITY;

DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename = 'ca_categories' AND policyname = 'ca_categories_owner') THEN
    CREATE POLICY ca_categories_owner ON ca_categories FOR ALL USING (user_id = auth.uid());
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename = 'ca_people' AND policyname = 'ca_people_owner') THEN
    CREATE POLICY ca_people_owner ON ca_people FOR ALL USING (user_id = auth.uid());
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename = 'ca_rules' AND policyname = 'ca_rules_owner') THEN
    CREATE POLICY ca_rules_owner ON ca_rules FOR ALL USING (user_id = auth.uid());
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename = 'ca_person_rules' AND policyname = 'ca_person_rules_owner') THEN
    CREATE POLICY ca_person_rules_owner ON ca_person_rules FOR ALL USING (user_id = auth.uid());
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename = 'ca_merchant_map' AND policyname = 'ca_merchant_map_owner') THEN
    CREATE POLICY ca_merchant_map_owner ON ca_merchant_map FOR ALL USING (user_id = auth.uid());
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename = 'ca_transactions' AND policyname = 'ca_transactions_owner') THEN
    CREATE POLICY ca_transactions_owner ON ca_transactions FOR ALL USING (user_id = auth.uid());
  END IF;
  -- Tabelle ponte: niente user_id proprio, RLS verifica il proprietario tramite la riga padre
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename = 'ca_merchant_map_categories' AND policyname = 'ca_merchant_map_categories_owner') THEN
    CREATE POLICY ca_merchant_map_categories_owner ON ca_merchant_map_categories FOR ALL
      USING (EXISTS (SELECT 1 FROM ca_merchant_map m WHERE m.id = merchant_map_id AND m.user_id = auth.uid()));
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename = 'ca_transaction_categories' AND policyname = 'ca_transaction_categories_owner') THEN
    CREATE POLICY ca_transaction_categories_owner ON ca_transaction_categories FOR ALL
      USING (EXISTS (SELECT 1 FROM ca_transactions t WHERE t.id = transaction_id AND t.user_id = auth.uid()));
  END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_ca_categories_parent ON ca_categories(parent_id);
CREATE INDEX IF NOT EXISTS idx_ca_transactions_user ON ca_transactions(user_id);
CREATE INDEX IF NOT EXISTS idx_ca_transactions_person ON ca_transactions(person_id);
CREATE INDEX IF NOT EXISTS idx_ca_transaction_categories_category ON ca_transaction_categories(category_id);
CREATE INDEX IF NOT EXISTS idx_ca_rules_user ON ca_rules(user_id);
CREATE INDEX IF NOT EXISTS idx_ca_person_rules_user ON ca_person_rules(user_id);
CREATE INDEX IF NOT EXISTS idx_ca_merchant_map_user_key ON ca_merchant_map(user_id, merchant_key);

-- Registrazione app nel launcher AppSphere
INSERT INTO cm_apps (title, description, score_query, color, active, html_file, riservato)
SELECT 'Analisi Costi', 'Import transazioni Revolut e categorizzazione automatica spese',
  'SELECT COUNT(*)::int FROM ca_transactions t WHERE t.user_id = auth.uid()
     AND NOT EXISTS (SELECT 1 FROM ca_transaction_categories tc WHERE tc.transaction_id = t.id)',
  '#EE334E', true, 'cost-analysis.html', true
WHERE NOT EXISTS (SELECT 1 FROM cm_apps WHERE title = 'Analisi Costi');
