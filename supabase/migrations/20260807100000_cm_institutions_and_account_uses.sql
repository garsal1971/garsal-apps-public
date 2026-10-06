-- ═══════════════════════════════════════════════════════════════════════════
-- Censimento degli istituti + un conto può servire a più moduli
--
-- Il modello precedente chiedeva all'utente di decidere tutto prima di sapere
-- qualcosa: banca, paese, IBAN, nome del conto, modulo e proprietario venivano
-- compilati PRIMA del consenso, cioè prima di sapere quali conti la banca
-- avrebbe restituito. Ora il collegamento è diviso in tre momenti distinti:
--
--   1. censimento  → cm_institutions: le mie banche e i miei broker
--   2. consenso    → parte dall'istituto, senza compilare niente
--   3. battesimo   → i conti tornati dalla banca ricevono un nome (display_name)
--                    e uno o più usi (uses)
--
-- Un conto scoperto ma non ancora battezzato ha display_name NULL e uses '{}':
-- esiste, si vede, ma nessun modulo lo usa.
-- ═══════════════════════════════════════════════════════════════════════════

-- ── 1. Il censimento ───────────────────────────────────────────────────────
-- connectable = false è il caso del broker senza PSD2 (Directa, Degiro…): sta
-- in anagrafica come gli altri, ma non ha un pulsante "Collega" perché non c'è
-- niente da chiamare. Per gli istituti collegabili aspsp_name deve combaciare
-- con il catalogo Enable Banking, che è l'unico nome che l'API riconosce.
CREATE TABLE IF NOT EXISTS cm_institutions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  name text NOT NULL,
  kind text NOT NULL DEFAULT 'banca',
  connectable boolean NOT NULL DEFAULT true,
  aspsp_name text,
  aspsp_country text,
  logo_url text,
  notes text,
  created_at timestamptz DEFAULT now()
);

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'cm_institutions_kind_check') THEN
    ALTER TABLE cm_institutions
      ADD CONSTRAINT cm_institutions_kind_check CHECK (kind IN ('banca', 'broker'));
  END IF;
  -- Un istituto collegabile senza nome ASPSP e paese produrrebbe una chiamata
  -- che non può che fallire: si blocca qui invece che davanti alla banca.
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'cm_institutions_connectable_check') THEN
    ALTER TABLE cm_institutions
      ADD CONSTRAINT cm_institutions_connectable_check
      CHECK (connectable = false OR (aspsp_name IS NOT NULL AND aspsp_country IS NOT NULL));
  END IF;
END $$;

ALTER TABLE cm_institutions ENABLE ROW LEVEL SECURITY;

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename = 'cm_institutions' AND policyname = 'cm_institutions_owner') THEN
    CREATE POLICY cm_institutions_owner ON cm_institutions FOR ALL
      USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());
  END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_cm_institutions_user ON cm_institutions(user_id);

-- ── 2. Le viste di compatibilità se ne vanno ───────────────────────────────
-- Sono SELECT * : finché esistono, Postgres rifiuta il DROP COLUMN module più
-- sotto. Nessun client le usa più (verificato su HTML ed Edge Function), ed
-- erano dichiarate rimovibili proprio a questa condizione.
DROP VIEW IF EXISTS ca_bank_connections;
DROP VIEW IF EXISTS ca_sync_log;

-- ── 3. Le colonne nuove del conto ──────────────────────────────────────────
ALTER TABLE cm_bank_connections
  ADD COLUMN IF NOT EXISTS institution_id uuid REFERENCES cm_institutions(id) ON DELETE SET NULL,
  ADD COLUMN IF NOT EXISTS iban text,
  ADD COLUMN IF NOT EXISTS uses text[] NOT NULL DEFAULT '{}';

DO $$
BEGIN
  -- I valori ammessi sono gli stessi tre che i client sanno leggere: una riga
  -- con un uso sconosciuto sparirebbe da ogni elenco senza dire perché.
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'cm_bank_connections_uses_check') THEN
    ALTER TABLE cm_bank_connections
      ADD CONSTRAINT cm_bank_connections_uses_check
      CHECK (uses <@ ARRAY['cost_analysis', 'fondo', 'conto_risparmio']::text[]);
  END IF;
END $$;

-- ── 4. I dati che ci sono già ──────────────────────────────────────────────
-- Un istituto per ogni banca già collegata. Il paese non è mai stato una
-- colonna (si rileggeva con una regex dal testo libero del log di consenso),
-- quindi qui si ricostruisce dalle tre banche realmente in uso; per una banca
-- ignota resta 'IT', correggibile a mano dall'anagrafica.
DO $$
DECLARE
  r record;
  v_country text;
  v_inst uuid;
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_name = 'cm_bank_connections' AND column_name = 'module') THEN
    RETURN;  -- migration già applicata
  END IF;

  FOR r IN SELECT DISTINCT user_id, aspsp_name FROM cm_bank_connections WHERE user_id IS NOT NULL LOOP
    v_country := CASE r.aspsp_name
      WHEN 'Revolut'   THEN 'LT'
      WHEN 'N26'       THEN 'DE'
      ELSE 'IT'
    END;
    SELECT id INTO v_inst FROM cm_institutions
      WHERE user_id = r.user_id AND aspsp_name = r.aspsp_name;
    IF v_inst IS NULL THEN
      INSERT INTO cm_institutions (user_id, name, kind, connectable, aspsp_name, aspsp_country)
        VALUES (r.user_id, r.aspsp_name, 'banca', true, r.aspsp_name, v_country)
        RETURNING id INTO v_inst;
    END IF;
    UPDATE cm_bank_connections SET institution_id = v_inst
      WHERE user_id = r.user_id AND aspsp_name = r.aspsp_name AND institution_id IS NULL;
  END LOOP;

  -- L'uso che il conto aveva finora diventa il primo (e per ora unico) uso.
  EXECUTE 'UPDATE cm_bank_connections SET uses = ARRAY[module] WHERE uses = ''{}''::text[]';

  -- display_name faceva due mestieri: nome dato dall'utente, oppure l'IBAN
  -- messo lì d'ufficio dal callback quando il nome mancava. Il secondo caso
  -- torna dov'era sempre dovuto stare e lascia il nome vuoto, così il conto
  -- risulta "da battezzare" invece di chiamarsi come una stringa di 27 cifre.
  UPDATE cm_bank_connections
     SET iban = display_name, display_name = NULL
   WHERE iban IS NULL
     AND display_name ~ '^[A-Z]{2}[0-9]{2}[A-Z0-9]{6,30}$';
END $$;

-- La diagnostica del consenso è stata ritirata: la causa del "consenso vuoto"
-- è nota (whitelist in restricted mode) e queste righe non servono più a
-- distinguere niente. cm_sync_log torna a essere solo il registro delle
-- sincronizzazioni.
DELETE FROM cm_sync_log WHERE status = 'consent_request';

-- ── 5. Via module ──────────────────────────────────────────────────────────
DROP INDEX IF EXISTS idx_cm_bank_connections_module;

ALTER TABLE cm_bank_connections DROP CONSTRAINT IF EXISTS cm_bank_connections_module_check;
ALTER TABLE cm_bank_connections DROP COLUMN IF EXISTS module;

CREATE INDEX IF NOT EXISTS idx_cm_bank_connections_uses ON cm_bank_connections USING gin (uses);
CREATE INDEX IF NOT EXISTS idx_cm_bank_connections_institution ON cm_bank_connections(institution_id);

-- ── 6. RLS: la policy diceva chi può leggere, non chi può scrivere ─────────
-- FOR ALL con il solo USING lascia passare un INSERT con user_id di un altro:
-- la riga non si rilegge, ma resta nel database di qualcun altro. Il WITH
-- CHECK è ciò che mancava.
DROP POLICY IF EXISTS cm_bank_connections_owner ON cm_bank_connections;
CREATE POLICY cm_bank_connections_owner ON cm_bank_connections FOR ALL
  USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());

DROP POLICY IF EXISTS cm_sync_log_owner ON cm_sync_log;
CREATE POLICY cm_sync_log_owner ON cm_sync_log FOR ALL
  USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());

COMMENT ON TABLE cm_institutions IS
  'Censimento di banche e broker. connectable = false per gli istituti senza PSD2: stanno in anagrafica ma non si collegano.';
COMMENT ON COLUMN cm_bank_connections.uses IS
  'A cosa serve il conto: cost_analysis (Spese Famiglia), fondo, conto_risparmio. Vuoto = conto scoperto e non ancora battezzato.';
COMMENT ON COLUMN cm_bank_connections.display_name IS
  'Nome dato dall''utente al conto. Mai riempito d''ufficio: se è vuoto il conto è da battezzare.';
