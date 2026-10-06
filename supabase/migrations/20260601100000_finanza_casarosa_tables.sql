-- Migration for Finanza, Casa Rosa and Contabilità
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- Portafogli
CREATE TABLE IF NOT EXISTS fnz_portfolios (
  id          uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     uuid          NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  name        text          NOT NULL,
  description text          NOT NULL DEFAULT '',
  color       text          NOT NULL DEFAULT '#4f46e5',
  ownership_percentage numeric(5, 2) NOT NULL DEFAULT 100,
  created_at  timestamptz   NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS fnz_portfolios_user_idx ON fnz_portfolios(user_id);
ALTER TABLE fnz_portfolios ENABLE ROW LEVEL SECURITY;
CREATE POLICY "fnz_portfolios_own" ON fnz_portfolios FOR ALL USING (user_id = auth.uid());

-- Categorie tag configurabili per utente
CREATE TABLE IF NOT EXISTS fnz_tag_categories (
  id          uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     uuid          NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  name        text          NOT NULL,
  options     text[]        NOT NULL DEFAULT '{}',
  created_at  timestamptz   NOT NULL DEFAULT now(),
  UNIQUE(user_id, name)
);
CREATE INDEX IF NOT EXISTS fnz_tag_categories_user_idx ON fnz_tag_categories(user_id);
ALTER TABLE fnz_tag_categories ENABLE ROW LEVEL SECURITY;
CREATE POLICY "fnz_tag_categories_own" ON fnz_tag_categories FOR ALL USING (user_id = auth.uid());

-- Prodotti finanziari
CREATE TABLE IF NOT EXISTS fnz_products (
  id          uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     uuid          NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  symbol      text          NOT NULL,
  name        text          NOT NULL,
  asset_type  text          NOT NULL DEFAULT 'stock',
  exchange    text          NOT NULL DEFAULT '',
  currency    text          NOT NULL DEFAULT 'EUR',
  tags        jsonb         NOT NULL DEFAULT '{}',
  isin        text,
  created_at  timestamptz   NOT NULL DEFAULT now(),
  UNIQUE(user_id, symbol)
);
CREATE INDEX IF NOT EXISTS fnz_products_user_idx ON fnz_products(user_id);
CREATE INDEX IF NOT EXISTS fnz_products_symbol_idx ON fnz_products(symbol);
ALTER TABLE fnz_products ENABLE ROW LEVEL SECURITY;
CREATE POLICY "fnz_products_own" ON fnz_products FOR ALL USING (user_id = auth.uid());

-- Dossier titoli
CREATE TABLE IF NOT EXISTS fnz_dossiers (
  id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     uuid        NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  bank_name   text        NOT NULL,
  title       text        NOT NULL,
  number      text        NOT NULL,
  notes       text        NOT NULL DEFAULT '',
  created_at  timestamptz NOT NULL DEFAULT now(),
  UNIQUE(user_id, bank_name, number)
);
CREATE INDEX IF NOT EXISTS fnz_dossiers_user_idx ON fnz_dossiers(user_id);
ALTER TABLE fnz_dossiers ENABLE ROW LEVEL SECURITY;
CREATE POLICY "fnz_dossiers_own" ON fnz_dossiers FOR ALL USING (user_id = auth.uid());

-- Transazioni (carico = BUY, scarico = SELL)
CREATE TABLE IF NOT EXISTS fnz_transactions (
  id           uuid            PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id      uuid            NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  portfolio_id uuid            NOT NULL REFERENCES fnz_portfolios(id) ON DELETE CASCADE,
  product_id   uuid            NOT NULL REFERENCES fnz_products(id)   ON DELETE RESTRICT,
  dossier_id   uuid            REFERENCES fnz_dossiers(id) ON DELETE SET NULL,
  type         text            NOT NULL CHECK (type IN ('BUY', 'SELL')),
  quantity     numeric(18, 8)  NOT NULL CHECK (quantity > 0),
  price        numeric(18, 4)  NOT NULL CHECK (price >= 0),
  commission   numeric(18, 4)  NOT NULL DEFAULT 0 CHECK (commission >= 0),
  date         date            NOT NULL,
  notes        text            NOT NULL DEFAULT '',
  created_at   timestamptz     NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS fnz_transactions_user_idx ON fnz_transactions(user_id);
CREATE INDEX IF NOT EXISTS fnz_transactions_portfolio_idx ON fnz_transactions(portfolio_id);
CREATE INDEX IF NOT EXISTS fnz_transactions_product_idx ON fnz_transactions(product_id);
CREATE INDEX IF NOT EXISTS fnz_transactions_dossier_idx ON fnz_transactions(dossier_id);
CREATE INDEX IF NOT EXISTS fnz_transactions_date_idx ON fnz_transactions(date DESC);
ALTER TABLE fnz_transactions ENABLE ROW LEVEL SECURITY;
CREATE POLICY "fnz_transactions_own" ON fnz_transactions FOR ALL USING (user_id = auth.uid());

-- Cache prezzi
CREATE TABLE IF NOT EXISTS fnz_price_cache (
  symbol       text          PRIMARY KEY,
  price        numeric(18, 4),
  prev_close   numeric(18, 4),
  change_amt   numeric(18, 4),
  change_pct   numeric(8, 4),
  currency     text          NOT NULL DEFAULT 'USD',
  market_state text          NOT NULL DEFAULT 'REGULAR',
  updated_at   timestamptz   NOT NULL DEFAULT now()
);
-- fnz_price_cache è condivisa, ma mettiamo RLS per sicurezza (solo lettura per autenticati)
ALTER TABLE fnz_price_cache ENABLE ROW LEVEL SECURITY;
CREATE POLICY "fnz_price_cache_read" ON fnz_price_cache FOR SELECT USING (auth.role() = 'authenticated');

-- Storicizzazione Prezzi
CREATE TABLE IF NOT EXISTS fnz_price_history (
  id           uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
  symbol       text          NOT NULL,
  price        numeric(18, 4) NOT NULL,
  price_date   date          NOT NULL DEFAULT CURRENT_DATE,
  created_at   timestamptz   NOT NULL DEFAULT now(),
  UNIQUE(symbol, price_date)
);
CREATE INDEX IF NOT EXISTS fnz_price_history_symbol_idx ON fnz_price_history(symbol);
CREATE INDEX IF NOT EXISTS fnz_price_history_date_idx ON fnz_price_history(price_date DESC);
ALTER TABLE fnz_price_history ENABLE ROW LEVEL SECURITY;
CREATE POLICY "fnz_price_history_read" ON fnz_price_history FOR SELECT USING (auth.role() = 'authenticated');

-- Trigger per sincronizzazione fnz_price_history da fnz_price_cache
CREATE OR REPLACE FUNCTION handle_price_history_sync()
RETURNS TRIGGER AS $$
BEGIN
  INSERT INTO fnz_price_history (symbol, price, price_date)
  VALUES (NEW.symbol, NEW.price, CURRENT_DATE)
  ON CONFLICT (symbol, price_date)
  DO UPDATE SET
    price = EXCLUDED.price,
    created_at = now();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS on_price_cache_update ON fnz_price_cache;
CREATE TRIGGER on_price_cache_update
AFTER INSERT OR UPDATE ON fnz_price_cache
FOR EACH ROW EXECUTE FUNCTION handle_price_history_sync();

-- Log per Edge Function
CREATE TABLE IF NOT EXISTS fn_logs (
  id          bigserial    PRIMARY KEY,
  created_at  timestamptz  NOT NULL DEFAULT now(),
  level       text         NOT NULL CHECK (level IN ('ERROR','WARN','INFO','DEBUG')),
  message     text         NOT NULL,
  data        jsonb,
  request_id  text
);
CREATE INDEX IF NOT EXISTS fn_logs_created_at_idx ON fn_logs (created_at DESC);
ALTER TABLE fn_logs ENABLE ROW LEVEL SECURITY;
CREATE POLICY "fn_logs_select" ON fn_logs FOR SELECT USING (auth.role() = 'authenticated');

-- Prestiti / Mutui
CREATE TABLE IF NOT EXISTS fnz_loans (
  id                    uuid           PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id               uuid           NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  type                  text           NOT NULL CHECK (type IN ('MUTUO', 'PRESTITO')),
  name                  text           NOT NULL DEFAULT '',
  capitale              numeric(18, 2) NOT NULL CHECK (capitale >= 0),
  rata                  numeric(18, 2),
  totale_rate           integer,
  giorno_prima_rata     date,
  data_inizio_prestito  date,
  interesse             numeric(10, 6),
  notes                 text           NOT NULL DEFAULT '',
  ownership_percentage  numeric(5, 2)  NOT NULL DEFAULT 100,
  created_at            timestamptz    NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS fnz_loans_user_idx ON fnz_loans(user_id);
ALTER TABLE fnz_loans ENABLE ROW LEVEL SECURITY;
CREATE POLICY "fnz_loans_own" ON fnz_loans FOR ALL USING (user_id = auth.uid());

-- Altri Asset
CREATE TABLE IF NOT EXISTS fnz_other_asset_types (
  id          uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     uuid          NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  name        text          NOT NULL,
  created_at  timestamptz   NOT NULL DEFAULT now(),
  UNIQUE(user_id, name)
);
CREATE INDEX IF NOT EXISTS fnz_other_asset_types_user_idx ON fnz_other_asset_types(user_id);
ALTER TABLE fnz_other_asset_types ENABLE ROW LEVEL SECURITY;
CREATE POLICY "fnz_other_asset_types_own" ON fnz_other_asset_types FOR ALL USING (user_id = auth.uid());

CREATE TABLE IF NOT EXISTS fnz_other_assets (
  id                    uuid           PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id               uuid           NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  title                 text           NOT NULL DEFAULT '',
  description           text           NOT NULL DEFAULT '',
  asset_type            text           NOT NULL DEFAULT '',
  value                 numeric(18, 2) NOT NULL DEFAULT 0,
  valuation_date        date           NOT NULL DEFAULT CURRENT_DATE,
  ownership_percentage  numeric(5, 2)  NOT NULL DEFAULT 100,
  notes                 text           NOT NULL DEFAULT '',
  created_at            timestamptz    NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS fnz_other_assets_user_idx ON fnz_other_assets(user_id);
ALTER TABLE fnz_other_assets ENABLE ROW LEVEL SECURITY;
CREATE POLICY "fnz_other_assets_own" ON fnz_other_assets FOR ALL USING (user_id = auth.uid());

-- Contabilità Cointestata
CREATE TABLE IF NOT EXISTS acct_transactions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid REFERENCES auth.users(id) ON DELETE CASCADE,
  anno integer NOT NULL,
  data date NOT NULL,
  persona text NOT NULL CHECK (persona IN ('TERESA', 'SALVATORE')),
  tipo text NOT NULL CHECK (tipo IN ('BONIFICO', 'ALTRO', 'MENSA')),
  importo numeric(12,2) NOT NULL,
  descrizione text DEFAULT '',
  causale text DEFAULT '',
  tipo_versamento text DEFAULT '',
  provenienza text,
  created_at timestamptz DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_acct_trans_user ON acct_transactions(user_id);
ALTER TABLE acct_transactions ENABLE ROW LEVEL SECURITY;
CREATE POLICY "acct_transactions_own" ON acct_transactions FOR ALL USING (user_id = auth.uid());

-- Integrazioni Bancarie
CREATE TABLE IF NOT EXISTS bank_integrations (
  id              uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id         uuid          NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  bank_name       text          NOT NULL,
  institution_id  text          NOT NULL,
  requisition_id  uuid          NOT NULL,
  account_id      uuid,
  status          text          NOT NULL DEFAULT 'PENDING',
  last_sync       timestamptz,
  created_at      timestamptz   NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS bank_integrations_user_idx ON bank_integrations(user_id);
ALTER TABLE bank_integrations ENABLE ROW LEVEL SECURITY;
CREATE POLICY "bank_integrations_own" ON bank_integrations FOR ALL USING (user_id = auth.uid());

-- Storicizzazione Dashboard
CREATE TABLE IF NOT EXISTS fnz_dashboard_snapshots (
  id               uuid            PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id          uuid            NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  snapshot_date    date            NOT NULL DEFAULT CURRENT_DATE,
  patrimonio_netto numeric(18, 2)  NOT NULL,
  asset_totali     numeric(18, 2)  NOT NULL,
  debiti_totali    numeric(18, 2)  NOT NULL,
  details          jsonb           NOT NULL DEFAULT '{}',
  portafogli_totali numeric(18, 2),
  created_at       timestamptz     NOT NULL DEFAULT now(),
  updated_at       timestamptz     NOT NULL DEFAULT now(),
  UNIQUE(user_id, snapshot_date)
);
CREATE INDEX IF NOT EXISTS fnz_idx_dashboard_snapshots_user_date ON fnz_dashboard_snapshots(user_id, snapshot_date DESC);
ALTER TABLE fnz_dashboard_snapshots ENABLE ROW LEVEL SECURITY;
CREATE POLICY "fnz_dashboard_snapshots_own" ON fnz_dashboard_snapshots FOR ALL USING (user_id = auth.uid());

-- CASA ROSA (prefisso cntrs_)
CREATE TABLE IF NOT EXISTS cntrs_categories (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid REFERENCES auth.users(id) ON DELETE CASCADE,
  name text NOT NULL,
  created_at timestamptz DEFAULT now(),
  UNIQUE(user_id, name)
);
ALTER TABLE cntrs_categories ENABLE ROW LEVEL SECURITY;
CREATE POLICY "cntrs_categories_own" ON cntrs_categories FOR ALL USING (user_id = auth.uid());

CREATE TABLE IF NOT EXISTS cntrs_transactions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid REFERENCES auth.users(id) ON DELETE CASCADE,
  anno integer NOT NULL,
  data date NOT NULL,
  descrizione text NOT NULL,
  importo_voce numeric(12,2) DEFAULT 0,
  category_id uuid REFERENCES cntrs_categories(id) ON DELETE SET NULL,
  note text DEFAULT '',
  created_at timestamptz DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_cntrs_trans_user ON cntrs_transactions(user_id);
ALTER TABLE cntrs_transactions ENABLE ROW LEVEL SECURITY;
CREATE POLICY "cntrs_transactions_own" ON cntrs_transactions FOR ALL USING (user_id = auth.uid());

CREATE TABLE IF NOT EXISTS cntrs_legacy_data (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid REFERENCES auth.users(id) ON DELETE CASCADE,
  foglio text NOT NULL,
  dati jsonb NOT NULL,
  created_at timestamptz DEFAULT now()
);
ALTER TABLE cntrs_legacy_data ENABLE ROW LEVEL SECURITY;
CREATE POLICY "cntrs_legacy_data_own" ON cntrs_legacy_data FOR ALL USING (user_id = auth.uid());

CREATE TABLE IF NOT EXISTS cntrs_saldi (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid REFERENCES auth.users(id) ON DELETE CASCADE,
  data date NOT NULL,
  saldo_contabile numeric(12,2) DEFAULT 0,
  saldo_disponibile numeric(12,2) DEFAULT 0,
  note text DEFAULT '',
  created_at timestamptz DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_cntrs_saldi_user ON cntrs_saldi(user_id);
ALTER TABLE cntrs_saldi ENABLE ROW LEVEL SECURITY;
CREATE POLICY "cntrs_saldi_own" ON cntrs_saldi FOR ALL USING (user_id = auth.uid());
