-- Spese Personali (spese-personali.html) — il conto personale di Salvatore.
--
-- Tabelle proprie sal_*, come già per Ada, e per la stessa ragione: le spese del conto
-- personale non devono comparire nei totali di Spese Famiglia. Le ca_* sono lette da quattro
-- punti diversi (cost-analysis.html, la vista in sola lettura di finanza.html e di
-- situazione-teresa.html, revolut-auto-categorize) e un campo di separazione avrebbe retto solo
-- finché ogni query si ricordava di filtrarlo: la prima che se ne dimentica mescola le due
-- contabilità in silenzio. Tabelle separate non possono sbagliare.
--
-- Differenza rispetto alle ada_*: qui si archiviano anche le ENTRATE, non solo le uscite, e la
-- pagina mostra un saldo. Lo schema è lo stesso — amount è già con segno — quindi non serve
-- nessuna colonna in più: cambia solo cosa la pagina propone all'import e cosa somma.

-- ── Super-categorie ─────────────────────────────────────────────────
-- Unico livello che porta il colore: le voci lo ereditano.
CREATE TABLE IF NOT EXISTS sal_super_categories (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id    uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  name       text NOT NULL,
  icon       text,
  color      text,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_sal_super_categories_user ON sal_super_categories(user_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_sal_super_categories_user_name
  ON sal_super_categories(user_id, lower(name));

-- ── Voci di spesa ───────────────────────────────────────────────────
-- super_id è ON DELETE SET NULL: cancellare una super-categoria non porta via le voci, e
-- quindi nessun movimento perde la sua categoria.
CREATE TABLE IF NOT EXISTS sal_categories (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id    uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  name       text NOT NULL,
  icon       text,
  super_id   uuid REFERENCES sal_super_categories(id) ON DELETE SET NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_sal_categories_user  ON sal_categories(user_id);
CREATE INDEX IF NOT EXISTS idx_sal_categories_super ON sal_categories(super_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_sal_categories_user_name ON sal_categories(user_id, lower(name));

-- ── Movimenti ───────────────────────────────────────────────────────
-- amount con segno come nel resto delle app: uscita < 0, entrata > 0. Qui entrambe vengono
-- archiviate, perché la pagina tiene anche il saldo del conto.
CREATE TABLE IF NOT EXISTS sal_transactions (
  id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id             uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  bank_connection_id  uuid REFERENCES cm_bank_connections(id) ON DELETE SET NULL,
  external_id         text,
  date                date NOT NULL,
  amount              numeric(12,2) NOT NULL,
  currency            text,
  description         text NOT NULL DEFAULT '',
  category_id         uuid REFERENCES sal_categories(id) ON DELETE SET NULL,
  card_identification text,
  type                text,
  import_source       text NOT NULL DEFAULT 'bank',
  raw                 jsonb,
  created_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_sal_transactions_user_date ON sal_transactions(user_id, date DESC);
CREATE INDEX IF NOT EXISTS idx_sal_transactions_category  ON sal_transactions(category_id);
-- Idempotenza dell'import: la stessa riga della banca non entra due volte. L'indice non è
-- parziale di proposito — Postgres non abbina un ON CONFLICT semplice a un indice parziale, e
-- le righe con NULL restano comunque distinte fra loro (stessa scelta di ada_transactions).
CREATE UNIQUE INDEX IF NOT EXISTS uq_sal_transactions_bank_external
  ON sal_transactions(bank_connection_id, external_id);

-- ── Negozi imparati ─────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS sal_merchant_map (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id      uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  merchant_key text NOT NULL,
  category_id  uuid NOT NULL REFERENCES sal_categories(id) ON DELETE CASCADE,
  updated_at   timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_sal_merchant_map_user_key ON sal_merchant_map(user_id, merchant_key);

-- ── RLS: ognuno vede solo le proprie righe ──────────────────────────
ALTER TABLE sal_super_categories ENABLE ROW LEVEL SECURITY;
ALTER TABLE sal_categories       ENABLE ROW LEVEL SECURITY;
ALTER TABLE sal_transactions     ENABLE ROW LEVEL SECURITY;
ALTER TABLE sal_merchant_map     ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS sal_super_categories_own ON sal_super_categories;
CREATE POLICY sal_super_categories_own ON sal_super_categories FOR ALL
  USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());

DROP POLICY IF EXISTS sal_categories_own ON sal_categories;
CREATE POLICY sal_categories_own ON sal_categories FOR ALL
  USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());

DROP POLICY IF EXISTS sal_transactions_own ON sal_transactions;
CREATE POLICY sal_transactions_own ON sal_transactions FOR ALL
  USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());

DROP POLICY IF EXISTS sal_merchant_map_own ON sal_merchant_map;
CREATE POLICY sal_merchant_map_own ON sal_merchant_map FOR ALL
  USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());

-- ── Un ottavo uso per i conti collegati ─────────────────────────────
-- Il conto UniCredit personale è già collegato: qui si aggiunge solo il valore che permette di
-- spuntarlo per questo modulo. uses è un array, quindi l'uso si somma a quelli che ha già.
-- Uso a sé e non 'spese_ada' riusato: le due pagine si ritroverebbero ciascuna il conto
-- dell'altra nella tendina dell'import, e sbagliare conto qui si paga a mano.
ALTER TABLE cm_bank_connections DROP CONSTRAINT IF EXISTS cm_bank_connections_uses_check;
ALTER TABLE cm_bank_connections
  ADD CONSTRAINT cm_bank_connections_uses_check
  CHECK (uses <@ ARRAY['cost_analysis', 'fondo', 'conto_risparmio', 'contribuzione',
                       'spese_ada', 'casa_rosa', 'danaro_rosa', 'spese_sal']::text[]);

COMMENT ON COLUMN cm_bank_connections.uses IS
  'A cosa serve il conto: cost_analysis (Spese Famiglia), fondo, conto_risparmio, contribuzione, spese_ada, casa_rosa, danaro_rosa, spese_sal (Spese Personali). Vuoto = conto scoperto e non ancora battezzato.';

-- ── Categorie di partenza ───────────────────────────────────────────
-- Una pagina che si apre senza nessuna categoria non permette di categorizzare niente, ed è la
-- prima cosa che si fa dopo un import. Si creano solo per Salvatore (è la sua contabilità) e
-- solo se non ce ne sono già, così rilanciare la migration non duplica niente.
INSERT INTO sal_categories (user_id, name, icon)
SELECT public.garsal_user_id(), v.name, v.icon
FROM (VALUES
  ('Spesa e alimentari', '🛒'),
  ('Casa e bollette',    '🏠'),
  ('Salute',             '💊'),
  ('Trasporti',          '🚗'),
  ('Svago',              '🎈'),
  ('Prelievi',           '🏧'),
  ('Entrate',            '💶'),
  ('Altro',              '📦')
) AS v(name, icon)
WHERE public.garsal_user_id() IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sal_categories WHERE user_id = public.garsal_user_id());

COMMENT ON TABLE sal_transactions IS
  'Spese e entrate del conto personale di Salvatore. Separate dalle ca_* di proposito: non devono entrare nei totali di Spese Famiglia.';
