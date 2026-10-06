-- Spese Ada (spese-ada.html) — contabilità separata da Spese Famiglia.
--
-- Perché tabelle nuove e non le ca_* con un campo in più: le spese di Ada NON devono comparire
-- nei totali di famiglia, e le ca_* sono lette da quattro punti diversi (cost-analysis.html, la
-- vista in sola lettura di finanza.html e di situazione-teresa.html, revolut-auto-categorize).
-- Un campo di separazione avrebbe funzionato solo finché ogni singola query ricordava di
-- filtrarlo: dimenticarne una avrebbe mescolato le spese in silenzio, che è esattamente
-- l'errore che non si vede finché non è tardi. Tabelle separate non possono sbagliare.
--
-- Rispetto alle ca_* lo schema è ridotto a quello che serve davvero qui: import, categorie e
-- dashboard. Niente persone, carte, viaggi, MCC map, regole testuali — e una sola categoria per
-- movimento invece della molti-a-molti, perché qui non serve dividere una spesa fra due voci.

-- ── Categorie ───────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ada_categories (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id    uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  name       text NOT NULL,
  icon       text,
  color      text,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_ada_categories_user ON ada_categories(user_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_ada_categories_user_name ON ada_categories(user_id, lower(name));

-- ── Movimenti ───────────────────────────────────────────────────────
-- amount con segno come nel resto delle app: spesa < 0, entrata > 0.
CREATE TABLE IF NOT EXISTS ada_transactions (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id            uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  bank_connection_id uuid REFERENCES cm_bank_connections(id) ON DELETE SET NULL,
  external_id        text,
  date               date NOT NULL,
  amount             numeric(12,2) NOT NULL,
  currency           text,
  description        text NOT NULL DEFAULT '',
  category_id        uuid REFERENCES ada_categories(id) ON DELETE SET NULL,
  card_identification text,
  type               text,
  import_source      text NOT NULL DEFAULT 'bank',
  raw                jsonb,
  created_at         timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_ada_transactions_user_date ON ada_transactions(user_id, date DESC);
CREATE INDEX IF NOT EXISTS idx_ada_transactions_category ON ada_transactions(category_id);
-- Idempotenza dell'import: la stessa riga della banca non entra due volte. L'indice non è
-- parziale di proposito — Postgres non abbina un ON CONFLICT semplice a un indice parziale, e
-- le righe con NULL restano comunque distinte fra loro (stessa scelta di ca_transactions).
CREATE UNIQUE INDEX IF NOT EXISTS uq_ada_transactions_bank_external
  ON ada_transactions(bank_connection_id, external_id);

-- ── Merchant appresi ────────────────────────────────────────────────
-- Quando si categorizza un movimento a mano, il negozio resta imparato: al prossimo import la
-- stessa descrizione prende la categoria da sola.
CREATE TABLE IF NOT EXISTS ada_merchant_map (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id      uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  merchant_key text NOT NULL,
  category_id  uuid NOT NULL REFERENCES ada_categories(id) ON DELETE CASCADE,
  updated_at   timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_ada_merchant_map_user_key ON ada_merchant_map(user_id, merchant_key);

-- ── RLS: ognuno vede solo le proprie righe ──────────────────────────
ALTER TABLE ada_categories   ENABLE ROW LEVEL SECURITY;
ALTER TABLE ada_transactions ENABLE ROW LEVEL SECURITY;
ALTER TABLE ada_merchant_map ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS ada_categories_own ON ada_categories;
CREATE POLICY ada_categories_own ON ada_categories FOR ALL
  USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());

DROP POLICY IF EXISTS ada_transactions_own ON ada_transactions;
CREATE POLICY ada_transactions_own ON ada_transactions FOR ALL
  USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());

DROP POLICY IF EXISTS ada_merchant_map_own ON ada_merchant_map;
CREATE POLICY ada_merchant_map_own ON ada_merchant_map FOR ALL
  USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());

-- ── Un quinto uso per i conti collegati ─────────────────────────────
-- Il conto UniCredit serve anche a Spese Ada: uses è un array, quindi l'uso si aggiunge a
-- quelli che ha già senza toglierne nessuno.
ALTER TABLE cm_bank_connections DROP CONSTRAINT IF EXISTS cm_bank_connections_uses_check;
ALTER TABLE cm_bank_connections
  ADD CONSTRAINT cm_bank_connections_uses_check
  CHECK (uses <@ ARRAY['cost_analysis', 'fondo', 'conto_risparmio', 'contribuzione', 'spese_ada']::text[]);

COMMENT ON COLUMN cm_bank_connections.uses IS
  'A cosa serve il conto: cost_analysis (Spese Famiglia), fondo, conto_risparmio, contribuzione, spese_ada. Vuoto = conto scoperto e non ancora battezzato.';

-- ── Categorie di partenza ───────────────────────────────────────────
-- Una pagina che si apre senza nessuna categoria non permette di categorizzare niente, e la
-- prima cosa che si fa dopo un import è proprio quello. Si creano solo per Salvatore (è la sua
-- contabilità) e solo se non ce ne sono già, così rilanciare la migration non duplica niente.
INSERT INTO ada_categories (user_id, name, icon, color)
SELECT public.garsal_user_id(), v.name, v.icon, v.color
FROM (VALUES
  ('Spesa e alimentari', '🛒', '#16a34a'),
  ('Casa e bollette',    '🏠', '#0891c8'),
  ('Salute e farmacia',  '💊', '#dc2626'),
  ('Trasporti',          '🚗', '#d97706'),
  ('Svago',              '🎈', '#7c3aed'),
  ('Altro',              '📦', '#64748b')
) AS v(name, icon, color)
WHERE public.garsal_user_id() IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM ada_categories WHERE user_id = public.garsal_user_id());

COMMENT ON TABLE ada_transactions IS
  'Spese di Ada. Separate dalle ca_* di proposito: non devono entrare nei totali di Spese Famiglia.';
