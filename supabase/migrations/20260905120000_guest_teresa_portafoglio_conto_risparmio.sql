-- =====================================================================
-- Teresa vede il PORTAFOGLIO del Conto Risparmio (situazione-teresa.html)
--
-- Stesso meccanismo di 20260716180000_guest_access_teresa.sql: policy di
-- sola lettura legate a has_page_access('situazione-teresa.html'), più le
-- restrittive che vietano ogni scrittura agli ospiti di questa pagina.
--
-- ⚠️ Le policy sono RISTRETTE AL SOLO CONTO RISPARMIO, non aperte su tutte
-- le tabelle fnz_*: il resto del patrimonio non è affare di questa pagina.
-- Il perno è il portafoglio che si chiama «CONTO RISPARMIO» (confronto
-- ILIKE, come per i mutui di Rosa in 20260611230000): da lui discendono i
-- movimenti, da questi i prodotti, dai prodotti i simboli dei prezzi, e
-- dal portafoglio il fondo collegato coi suoi versamenti.
--
-- ⚠️ Se un giorno quel portafoglio viene rinominato senza la parola
-- «risparmio» dentro, la sezione della pagina si svuota SENZA nessun
-- errore: non è un difetto della pagina, è questo filtro che non aggancia
-- più. Sta scritto anche in CLAUDE.md e nel commento del blocco JS.
-- =====================================================================

-- ── Cosa si può vedere, in quattro funzioni ─────────────────────────
-- SECURITY DEFINER per due ragioni: leggono tabelle le cui policy, dentro
-- la policy di un'altra tabella, non varrebbero comunque; e soprattutto
-- così le policy NON SI ANNIDANO una dentro l'altra — la policy dei
-- prezzi non finisce a valutare quella dei prodotti, che valuterebbe
-- quella dei movimenti, che valuterebbe quella dei portafogli.
CREATE OR REPLACE FUNCTION public.teresa_cr_portfolios()
RETURNS SETOF uuid
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public
AS $$
  SELECT id FROM fnz_portfolios
  WHERE name ILIKE '%conto risparmio%'
    -- Le tabelle fnz_* non sono monoutente: senza questo filtro un
    -- portafoglio chiamato così da chiunque altro finirebbe qui dentro.
    AND user_id = public.garsal_user_id()
$$;

CREATE OR REPLACE FUNCTION public.teresa_cr_products()
RETURNS SETOF uuid
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public
AS $$
  SELECT DISTINCT t.product_id FROM fnz_transactions t
  WHERE t.portfolio_id IN (SELECT * FROM public.teresa_cr_portfolios())
    AND t.product_id IS NOT NULL
$$;

CREATE OR REPLACE FUNCTION public.teresa_cr_symbols()
RETURNS SETOF text
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public
AS $$
  SELECT DISTINCT p.symbol FROM fnz_products p
  WHERE p.id IN (SELECT * FROM public.teresa_cr_products())
    AND p.symbol IS NOT NULL
$$;

CREATE OR REPLACE FUNCTION public.teresa_cr_funds()
RETURNS SETOF uuid
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public
AS $$
  SELECT id FROM fnz_funds
  WHERE linked_portfolio_id IN (SELECT * FROM public.teresa_cr_portfolios())
$$;

-- Le policy si valutano coi privilegi di chi interroga: senza l'EXECUTE
-- ad anon, una query anonima su queste tabelle darebbe «permission denied
-- for function» invece dell'elenco vuoto che ha sempre dato.
DO $$
DECLARE f text;
BEGIN
  FOREACH f IN ARRAY ARRAY['teresa_cr_portfolios','teresa_cr_products','teresa_cr_symbols','teresa_cr_funds'] LOOP
    EXECUTE format('REVOKE ALL ON FUNCTION public.%I() FROM PUBLIC', f);
    EXECUTE format('GRANT EXECUTE ON FUNCTION public.%I() TO anon, authenticated, service_role', f);
  END LOOP;
END $$;

-- ── Lettura ──────────────────────────────────────────────────────────
DROP POLICY IF EXISTS "guest_teresa_read_fnz_portfolios" ON fnz_portfolios;
CREATE POLICY "guest_teresa_read_fnz_portfolios" ON fnz_portfolios
  FOR SELECT USING (
    public.has_page_access('situazione-teresa.html')
    AND id IN (SELECT * FROM public.teresa_cr_portfolios())
  );

DROP POLICY IF EXISTS "guest_teresa_read_fnz_transactions" ON fnz_transactions;
CREATE POLICY "guest_teresa_read_fnz_transactions" ON fnz_transactions
  FOR SELECT USING (
    public.has_page_access('situazione-teresa.html')
    AND portfolio_id IN (SELECT * FROM public.teresa_cr_portfolios())
  );

-- I soli prodotti che in quel portafoglio compaiono davvero: il catalogo
-- è comune a tutti i portafogli, e aprirlo intero direbbe cosa è stato
-- comprato altrove.
DROP POLICY IF EXISTS "guest_teresa_read_fnz_products" ON fnz_products;
CREATE POLICY "guest_teresa_read_fnz_products" ON fnz_products
  FOR SELECT USING (
    public.has_page_access('situazione-teresa.html')
    AND id IN (SELECT * FROM public.teresa_cr_products())
  );

-- Stessa restrizione un gradino più in là: i prezzi dei soli simboli di
-- quei prodotti. Alla pagina basta la cache — una riga per simbolo, con
-- dentro anche la chiusura precedente — quindi lo storico resta fuori.
-- ⚠️ La policy generica di questa tabella è ristretta a is_garsal()
-- (20260611160000): questa si aggiunge, non la sostituisce.
DROP POLICY IF EXISTS "guest_teresa_read_fnz_price_cache" ON fnz_price_cache;
CREATE POLICY "guest_teresa_read_fnz_price_cache" ON fnz_price_cache
  FOR SELECT USING (
    public.has_page_access('situazione-teresa.html')
    AND symbol IN (SELECT * FROM public.teresa_cr_symbols())
  );

-- Il fondo collegato a quel portafoglio: è lui a dire di chi è quanto.
DROP POLICY IF EXISTS "guest_teresa_read_fnz_funds" ON fnz_funds;
CREATE POLICY "guest_teresa_read_fnz_funds" ON fnz_funds
  FOR SELECT USING (
    public.has_page_access('situazione-teresa.html')
    AND id IN (SELECT * FROM public.teresa_cr_funds())
  );

DROP POLICY IF EXISTS "guest_teresa_read_fnz_fund_contributions" ON fnz_fund_contributions;
CREATE POLICY "guest_teresa_read_fnz_fund_contributions" ON fnz_fund_contributions
  FOR SELECT USING (
    public.has_page_access('situazione-teresa.html')
    AND fund_id IN (SELECT * FROM public.teresa_cr_funds())
  );

DROP POLICY IF EXISTS "guest_teresa_read_fnz_fund_participants" ON fnz_fund_participants;
CREATE POLICY "guest_teresa_read_fnz_fund_participants" ON fnz_fund_participants
  FOR SELECT USING (
    public.has_page_access('situazione-teresa.html')
    AND fund_id IN (SELECT * FROM public.teresa_cr_funds())
  );

-- L'indice ISTAT FOI: è un dato pubblico (media annua dei prezzi al
-- consumo), non dice niente di nessuno, e senza di lui le quote rivalutate
-- non si possono calcolare — quindi si legge senza filtri sulle righe.
-- ⚠️ Ma le sole righe di Salvatore (garsal_user_id, come per le ca_*):
-- la tabella non è monoutente, e due righe per lo stesso anno darebbero
-- alla pagina un coefficiente scelto a caso fra i due.
DROP POLICY IF EXISTS "guest_teresa_read_fnz_foi_index" ON fnz_foi_index;
CREATE POLICY "guest_teresa_read_fnz_foi_index" ON fnz_foi_index
  FOR SELECT USING (
    public.has_page_access('situazione-teresa.html')
    AND user_id = public.garsal_user_id()
  );

-- ── Blocco scritture per gli ospiti di questa pagina ────────────────
-- (is_garsal() OR ... per non bloccare mai il proprietario; il service
-- role la RLS non la vede affatto, quindi get-prices continua a scrivere.)
DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['fnz_portfolios','fnz_transactions','fnz_products','fnz_price_cache',
                           'fnz_funds','fnz_fund_contributions','fnz_fund_participants','fnz_foi_index'] LOOP
    EXECUTE format('DROP POLICY IF EXISTS "guest_teresa_no_insert" ON %I', t);
    EXECUTE format('DROP POLICY IF EXISTS "guest_teresa_no_update" ON %I', t);
    EXECUTE format('DROP POLICY IF EXISTS "guest_teresa_no_delete" ON %I', t);
    EXECUTE format('CREATE POLICY "guest_teresa_no_insert" ON %I AS RESTRICTIVE FOR INSERT WITH CHECK (public.is_garsal() OR NOT public.has_page_access(''situazione-teresa.html''))', t);
    EXECUTE format('CREATE POLICY "guest_teresa_no_update" ON %I AS RESTRICTIVE FOR UPDATE USING (public.is_garsal() OR NOT public.has_page_access(''situazione-teresa.html''))', t);
    EXECUTE format('CREATE POLICY "guest_teresa_no_delete" ON %I AS RESTRICTIVE FOR DELETE USING (public.is_garsal() OR NOT public.has_page_access(''situazione-teresa.html''))', t);
  END LOOP;
END $$;
