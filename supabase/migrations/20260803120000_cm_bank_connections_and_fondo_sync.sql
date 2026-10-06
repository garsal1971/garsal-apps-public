-- ═══════════════════════════════════════════════════════════════════════════
-- Parte 1 — I conti bancari diventano condivisi tra moduli
--
-- ca_bank_connections e ca_sync_log nascono dentro Analisi Costi, ma un conto
-- collegato via Enable Banking non è "di Analisi Costi": ora serve anche al
-- modulo Fondi (conto UniCredit da cui arrivano versamenti e prelievi). Le due
-- tabelle passano quindi al prefisso condiviso cm_.
--
-- Le viste ca_bank_connections / ca_sync_log restano come compatibilità: le
-- Edge Function vengono ridistribuite solo quando il commit le tocca, quindi
-- senza le viste una function non aggiornata smetterebbe di funzionare in
-- silenzio. Sono viste semplici su una sola tabella, quindi auto-aggiornabili
-- (INSERT/UPDATE/DELETE passano alla tabella sottostante), con
-- security_invoker = true perché la RLS da applicare è quella di chi chiama,
-- non quella del proprietario della vista.
-- ═══════════════════════════════════════════════════════════════════════════

-- Rename idempotente: si esegue solo se esiste ancora la TABELLA con il vecchio
-- nome (relkind = 'r'). Senza il controllo su relkind, una seconda esecuzione
-- rinominerebbe la vista di compatibilità creata più sotto.
DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public' AND c.relname = 'ca_bank_connections' AND c.relkind = 'r'
  ) AND NOT EXISTS (
    SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public' AND c.relname = 'cm_bank_connections'
  ) THEN
    ALTER TABLE public.ca_bank_connections RENAME TO cm_bank_connections;
  END IF;

  IF EXISTS (
    SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public' AND c.relname = 'ca_sync_log' AND c.relkind = 'r'
  ) AND NOT EXISTS (
    SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public' AND c.relname = 'cm_sync_log'
  ) THEN
    ALTER TABLE public.ca_sync_log RENAME TO cm_sync_log;
  END IF;
END $$;

-- Indici e policy seguono la tabella nel rename ma conservano il vecchio nome:
-- allinearli evita di ritrovarsi una policy "ca_" su una tabella "cm_".
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_class WHERE relname = 'idx_ca_bank_connections_user') THEN
    ALTER INDEX idx_ca_bank_connections_user RENAME TO idx_cm_bank_connections_user;
  END IF;
  IF EXISTS (SELECT 1 FROM pg_class WHERE relname = 'idx_ca_sync_log_connection') THEN
    ALTER INDEX idx_ca_sync_log_connection RENAME TO idx_cm_sync_log_connection;
  END IF;
  IF EXISTS (SELECT 1 FROM pg_policies WHERE tablename = 'cm_bank_connections' AND policyname = 'ca_bank_connections_owner') THEN
    ALTER POLICY ca_bank_connections_owner ON cm_bank_connections RENAME TO cm_bank_connections_owner;
  END IF;
  IF EXISTS (SELECT 1 FROM pg_policies WHERE tablename = 'cm_sync_log' AND policyname = 'ca_sync_log_owner') THEN
    ALTER POLICY ca_sync_log_owner ON cm_sync_log RENAME TO cm_sync_log_owner;
  END IF;
END $$;

-- Se il DB è nuovo (le tabelle ca_ non sono mai esistite) le tabelle vanno create.
CREATE TABLE IF NOT EXISTS cm_bank_connections (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid REFERENCES auth.users(id) ON DELETE CASCADE,
  provider text NOT NULL DEFAULT 'enable_banking',
  aspsp_name text NOT NULL,
  display_name text,
  owner_person_id uuid REFERENCES ca_people(id) ON DELETE SET NULL,
  account_id text,
  consent_id text,
  consent_expires_at timestamptz,
  status text NOT NULL DEFAULT 'active',
  created_at timestamptz DEFAULT now()
);

CREATE TABLE IF NOT EXISTS cm_sync_log (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid REFERENCES auth.users(id) ON DELETE CASCADE,
  bank_connection_id uuid REFERENCES cm_bank_connections(id) ON DELETE CASCADE,
  started_at timestamptz DEFAULT now(),
  finished_at timestamptz,
  status text,
  imported_count int DEFAULT 0,
  error_message text
);

-- A quale modulo serve il conto: decide in quale pagina l'utente lo ritrova e
-- dove lo riporta il redirect dopo il consenso sulla banca.
--   'cost_analysis' → Spese Famiglia (Revolut, comportamento storico)
--   'fondo'         → modulo Fondi di finanza.html (UniCredit)
ALTER TABLE cm_bank_connections ADD COLUMN IF NOT EXISTS module text NOT NULL DEFAULT 'cost_analysis';

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'cm_bank_connections_module_check') THEN
    ALTER TABLE cm_bank_connections
      ADD CONSTRAINT cm_bank_connections_module_check CHECK (module IN ('cost_analysis', 'fondo'));
  END IF;
END $$;

ALTER TABLE cm_bank_connections ENABLE ROW LEVEL SECURITY;
ALTER TABLE cm_sync_log ENABLE ROW LEVEL SECURITY;

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename = 'cm_bank_connections' AND policyname = 'cm_bank_connections_owner') THEN
    CREATE POLICY cm_bank_connections_owner ON cm_bank_connections FOR ALL USING (user_id = auth.uid());
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename = 'cm_sync_log' AND policyname = 'cm_sync_log_owner') THEN
    CREATE POLICY cm_sync_log_owner ON cm_sync_log FOR ALL USING (user_id = auth.uid());
  END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_cm_bank_connections_user ON cm_bank_connections(user_id);
CREATE INDEX IF NOT EXISTS idx_cm_bank_connections_module ON cm_bank_connections(module);
CREATE INDEX IF NOT EXISTS idx_cm_sync_log_connection ON cm_sync_log(bank_connection_id);

CREATE OR REPLACE VIEW ca_bank_connections WITH (security_invoker = true) AS
  SELECT * FROM cm_bank_connections;
CREATE OR REPLACE VIEW ca_sync_log WITH (security_invoker = true) AS
  SELECT * FROM cm_sync_log;

GRANT SELECT, INSERT, UPDATE, DELETE ON ca_bank_connections TO authenticated, service_role;
GRANT SELECT, INSERT, UPDATE, DELETE ON ca_sync_log         TO authenticated, service_role;

COMMENT ON VIEW ca_bank_connections IS
  'Compatibilità: la tabella è cm_bank_connections. Vista da rimuovere quando nessun client usa più il vecchio nome.';
COMMENT ON VIEW ca_sync_log IS
  'Compatibilità: la tabella è cm_sync_log. Vista da rimuovere quando nessun client usa più il vecchio nome.';

-- ═══════════════════════════════════════════════════════════════════════════
-- Parte 2 — Modulo Fondi: partecipanti anagrafici e movimenti importati
--
-- Finora un versamento portava il nome del partecipante come testo libero
-- (fnz_fund_contributions.participant) e poteva essere solo positivo. Per
-- importare i movimenti dal conto UniCredit servono: un'anagrafica con l'IBAN
-- (chiave di match della controparte), i prelievi (importo negativo) e lo stato
-- della riga, perché un movimento senza controparte riconosciuta non deve
-- entrare nel calcolo delle quote ma nemmeno sparire.
--
-- fnz_fund_contributions.participant resta popolato anche per le righe
-- importate: è quello che l'app mostra e continua a far funzionare il fondo
-- "Crypto" storico, che partecipanti anagrafici non ne ha.
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS fnz_fund_participants (
  id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id    uuid        NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  fund_id    uuid        NOT NULL REFERENCES fnz_funds(id) ON DELETE CASCADE,
  name       text        NOT NULL,
  iban       text,
  active     boolean     NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS fnz_fund_participants_fund_idx ON fnz_fund_participants(fund_id);
CREATE INDEX IF NOT EXISTS fnz_fund_participants_user_idx ON fnz_fund_participants(user_id);

-- L'IBAN è la chiave di match primaria del sync: due partecipanti dello stesso
-- fondo non possono averlo uguale, altrimenti l'attribuzione sarebbe ambigua.
-- Confronto normalizzato (maiuscolo, senza spazi) perché l'IBAN si scrive in
-- entrambi i modi.
CREATE UNIQUE INDEX IF NOT EXISTS fnz_fund_participants_iban_uq
  ON fnz_fund_participants(fund_id, upper(replace(iban, ' ', '')))
  WHERE iban IS NOT NULL AND iban <> '';
CREATE UNIQUE INDEX IF NOT EXISTS fnz_fund_participants_name_uq
  ON fnz_fund_participants(fund_id, upper(btrim(name)));

ALTER TABLE fnz_fund_participants ENABLE ROW LEVEL SECURITY;
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename = 'fnz_fund_participants' AND policyname = 'fnz_fund_participants_own') THEN
    CREATE POLICY "fnz_fund_participants_own" ON fnz_fund_participants FOR ALL USING (user_id = auth.uid());
  END IF;
END $$;

ALTER TABLE fnz_fund_contributions
  ADD COLUMN IF NOT EXISTS participant_id     uuid REFERENCES fnz_fund_participants(id) ON DELETE SET NULL,
  ADD COLUMN IF NOT EXISTS type               text NOT NULL DEFAULT 'versamento',
  ADD COLUMN IF NOT EXISTS status             text NOT NULL DEFAULT 'confermato',
  ADD COLUMN IF NOT EXISTS external_id        text,
  ADD COLUMN IF NOT EXISTS bank_connection_id uuid REFERENCES cm_bank_connections(id) ON DELETE SET NULL,
  ADD COLUMN IF NOT EXISTS amount_adjusted    numeric(14, 2),
  ADD COLUMN IF NOT EXISTS counterparty_name  text,
  ADD COLUMN IF NOT EXISTS counterparty_iban  text,
  ADD COLUMN IF NOT EXISTS raw                jsonb;

-- Il CHECK (amount > 0) originale rendeva impossibile registrare un prelievo.
-- Il segno È il tipo: versamento positivo, prelievo negativo, mai zero.
ALTER TABLE fnz_fund_contributions DROP CONSTRAINT IF EXISTS fnz_fund_contributions_amount_check;
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fnz_fund_contributions_type_check') THEN
    ALTER TABLE fnz_fund_contributions
      ADD CONSTRAINT fnz_fund_contributions_type_check CHECK (type IN ('versamento', 'prelievo'));
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fnz_fund_contributions_status_check') THEN
    ALTER TABLE fnz_fund_contributions
      ADD CONSTRAINT fnz_fund_contributions_status_check
      CHECK (status IN ('auto', 'confermato', 'escluso', 'da_rivedere'));
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fnz_fund_contributions_amount_sign') THEN
    ALTER TABLE fnz_fund_contributions
      ADD CONSTRAINT fnz_fund_contributions_amount_sign
      CHECK ((type = 'versamento' AND amount > 0) OR (type = 'prelievo' AND amount < 0));
  END IF;
END $$;

-- Idempotenza del sync: la stessa transazione bancaria non entra due volte.
CREATE UNIQUE INDEX IF NOT EXISTS fnz_fund_contributions_external_uq
  ON fnz_fund_contributions(bank_connection_id, external_id)
  WHERE bank_connection_id IS NOT NULL AND external_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS fnz_fund_contributions_status_idx ON fnz_fund_contributions(fund_id, status);

-- Conto da cui il fondo importa i movimenti (nullo = fondo solo manuale).
ALTER TABLE fnz_funds
  ADD COLUMN IF NOT EXISTS bank_connection_id uuid REFERENCES cm_bank_connections(id) ON DELETE SET NULL;

-- ═══════════════════════════════════════════════════════════════════════════
-- Parte 3 — Rivalutazione ISTAT FOI persistita
--
-- amount_adjusted è una cache: la formula di verità resta quella applicata a
-- schermo da computeFundShares() in finanza.html, identica a questa —
-- importo × (FOI anno di riferimento / FOI anno del movimento), dove l'anno di
-- riferimento è quello del primo movimento del fondo (per il fondo UniCredit,
-- il 15/2/2022). La colonna serve a chi legge i movimenti senza ricalcolare
-- (sync, export, query dirette) e va rigenerata quando si aggiunge un movimento
-- o si tocca l'indice FOI: l'app chiama questa funzione dopo ogni scrittura.
--
-- SECURITY INVOKER: la funzione lavora solo su righe che il chiamante può già
-- vedere, la RLS di fnz_fund_contributions e fnz_foi_index resta attiva. Il
-- proprietario si ricava dal fondo e non da auth.uid(), perché la chiama anche
-- la Edge Function del sync con la service role key, dove auth.uid() è NULL:
-- con la RLS attiva un utente non riesce comunque a leggere un fondo altrui,
-- quindi il SELECT sul fondo non torna nulla e la funzione non fa niente.
-- ═══════════════════════════════════════════════════════════════════════════
CREATE OR REPLACE FUNCTION fnz_fund_recalc_adjusted(p_fund_id uuid)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY INVOKER
AS $$
DECLARE
  v_user_id  uuid;
  v_ref_year int;
  v_ref_idx  numeric;
  v_updated  int;
BEGIN
  SELECT user_id INTO v_user_id FROM fnz_funds WHERE id = p_fund_id;
  IF v_user_id IS NULL THEN
    RETURN jsonb_build_object('ok', false, 'error', 'Fondo non trovato.');
  END IF;

  -- Anno di riferimento: primo movimento che conta davvero (le righe escluse o
  -- ancora da rivedere non spostano il riferimento di tutto il fondo).
  SELECT min(extract(year FROM date))::int INTO v_ref_year
  FROM fnz_fund_contributions
  WHERE fund_id = p_fund_id AND status IN ('auto', 'confermato');

  IF v_ref_year IS NULL THEN
    RETURN jsonb_build_object('ok', true, 'updated', 0, 'reference_year', null);
  END IF;

  SELECT index_value INTO v_ref_idx
  FROM fnz_foi_index
  WHERE year = v_ref_year AND user_id = v_user_id;

  IF v_ref_idx IS NULL THEN
    RETURN jsonb_build_object('ok', false, 'error',
      'Manca l''indice FOI dell''anno di riferimento ' || v_ref_year);
  END IF;

  UPDATE fnz_fund_contributions c
  SET amount_adjusted = round(c.amount * (v_ref_idx / f.index_value), 2)
  FROM fnz_foi_index f
  WHERE c.fund_id = p_fund_id
    AND f.user_id = c.user_id
    AND f.year = extract(year FROM c.date)::int;
  GET DIAGNOSTICS v_updated = ROW_COUNT;

  -- Anni senza indice FOI: meglio nessun valore che un valore sbagliato.
  UPDATE fnz_fund_contributions c
  SET amount_adjusted = NULL
  WHERE c.fund_id = p_fund_id
    AND NOT EXISTS (
      SELECT 1 FROM fnz_foi_index f
      WHERE f.user_id = c.user_id AND f.year = extract(year FROM c.date)::int
    );

  RETURN jsonb_build_object('ok', true, 'updated', v_updated, 'reference_year', v_ref_year);
END $$;

GRANT EXECUTE ON FUNCTION fnz_fund_recalc_adjusted(uuid) TO authenticated;
