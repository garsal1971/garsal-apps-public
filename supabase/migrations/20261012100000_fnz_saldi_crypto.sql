-- Portafoglio CRYPTO: le quantità si scrivono a mano, e ogni aggiornamento resta nel registro.
--
-- Fino a qui ogni portafoglio era la somma dei suoi carichi e scarichi (`fnz_transactions`):
-- la quantità di BTC era tutti gli acquisti meno tutte le vendite. Per le crypto non regge —
-- staking, commissioni in moneta, spostamenti fra wallet e piccole conversioni non sono
-- carichi né scarichi, e tenerli allineati a forza di transazioni finte è un lavoro che non
-- finisce mai. Da qui in poi per quel portafoglio si scrive direttamente la QUANTITÀ di ogni
-- moneta, e il valore resta quantità × prezzo coi prezzi di `get-prices`.
--
-- ⚠️ `fnz_portfolios.gestione` dice quale delle due regole vale:
--      'transazioni' — la regola di sempre (DEFAULT, tutti gli altri portafogli)
--      'saldi'       — vale l'ultima quantità scritta in `fnz_saldi_crypto`
--   Le transazioni di un portafoglio a saldi RESTANO in archivio e non si cancellano: non
--   contano più per le posizioni, ma raccontano com'era prima.
--
-- ⚠️ Niente costo: su un portafoglio a saldi `fnz_posizioni` restituisce `cost_basis` NULL,
--   e i client lo leggono come «costo sconosciuto» — niente P&L e niente tasse su quelle
--   posizioni, non uno zero (che direbbe che tutto il valore è guadagno).
--
-- ⚠️ Il registro si scrive e non si riscrive: ogni aggiornamento è una riga nuova, e la
--   quantità in vigore è la più recente per (portafoglio, moneta). Zero è una risposta buona —
--   «non ne ho più» — e fa sparire la posizione.

ALTER TABLE fnz_portfolios
  ADD COLUMN IF NOT EXISTS gestione text NOT NULL DEFAULT 'transazioni';
DO $$ BEGIN
  ALTER TABLE fnz_portfolios
    ADD CONSTRAINT fnz_portfolios_gestione_check CHECK (gestione IN ('transazioni', 'saldi'));
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

COMMENT ON COLUMN fnz_portfolios.gestione IS
  '''transazioni'' = posizioni dai carichi/scarichi; ''saldi'' = posizioni dall''ultima quantità scritta in fnz_saldi_crypto (senza costo).';

CREATE TABLE IF NOT EXISTS fnz_saldi_crypto (
  id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id      uuid        NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
  portfolio_id uuid        NOT NULL REFERENCES fnz_portfolios(id) ON DELETE CASCADE,
  product_id   uuid        NOT NULL REFERENCES fnz_products(id) ON DELETE CASCADE,
  qty          numeric     NOT NULL CHECK (qty >= 0),
  recorded_at  timestamptz NOT NULL DEFAULT now(),
  note         text,
  created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS fnz_saldi_crypto_pf_idx
  ON fnz_saldi_crypto(portfolio_id, product_id, recorded_at DESC);
ALTER TABLE fnz_saldi_crypto ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS "fnz_saldi_crypto_own" ON fnz_saldi_crypto;
CREATE POLICY "fnz_saldi_crypto_own" ON fnz_saldi_crypto
  FOR ALL USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());

COMMENT ON TABLE fnz_saldi_crypto IS
  'Registro delle quantità scritte a mano per i portafogli con gestione = ''saldi''. Una riga per aggiornamento; vale la più recente per (portafoglio, prodotto).';

-- ── Il punto di partenza: le quantità di oggi, ricavate dalle transazioni ──────
--
-- ⚠️ Si legge `fnz_posizioni` PRIMA di sostituirla e prima di passare il portafoglio a
--   'saldi': è la regola vecchia che dice quanto c'è adesso. Gira una volta sola — se il
--   portafoglio è già a saldi non si tocca niente — e salta senza fallire dove il
--   portafoglio non c'è (progetto dev).
DO $$
DECLARE
  v_pf   record;
  v_n    integer;
BEGIN
  FOR v_pf IN
    SELECT id, user_id FROM fnz_portfolios
     WHERE upper(trim(name)) = 'CRYPTO' AND gestione = 'transazioni'
  LOOP
    INSERT INTO fnz_saldi_crypto (user_id, portfolio_id, product_id, qty, note)
    SELECT v_pf.user_id, v_pf.id, pos.product_id, pos.qty,
           'Saldo iniziale, ricavato dalle transazioni'
      FROM public.fnz_posizioni(v_pf.user_id) pos
     WHERE pos.scope = 'portfolio' AND pos.scope_id = v_pf.id;
    GET DIAGNOSTICS v_n = ROW_COUNT;

    UPDATE fnz_portfolios SET gestione = 'saldi' WHERE id = v_pf.id;
    RAISE NOTICE 'Portafoglio CRYPTO % passato a saldi: % monete nel registro', v_pf.id, v_n;
  END LOOP;
  IF NOT FOUND THEN
    RAISE NOTICE 'Nessun portafoglio CRYPTO da convertire';
  END IF;
END $$;

-- ── fnz_posizioni: i portafogli a saldi leggono il registro ─────────────────────
--
-- Identica alla versione di `20260924140000_fnz_posizioni_liquidita.sql` per tutto il resto:
-- i portafogli 'transazioni' e i dossier non cambiano di una riga. Per i portafogli 'saldi'
-- le transazioni si saltano e si prende l'ultima quantità del registro, con costo NULL.
-- `ordine` è l'ordine in cui le monete sono comparse nel registro.
CREATE OR REPLACE FUNCTION public.fnz_posizioni(p_user uuid DEFAULT NULL)
RETURNS TABLE (
  scope      text,
  scope_id   uuid,
  product_id uuid,
  qty        numeric,
  cost_basis numeric,
  ordine     integer
)
LANGUAGE plpgsql STABLE SECURITY INVOKER SET search_path = public
AS $$
DECLARE
  r          record;
  v_scope    text;
  v_scope_id uuid;
  v_prod     uuid;
  v_qty      numeric := 0;
  v_cost     numeric := 0;
  v_primo    text;
  v_out      jsonb := '[]'::jsonb;
BEGIN
  FOR r IN
    SELECT s.scope, s.scope_id, s.product_id, s.type, s.quantity, s.price, s.commission,
           s.date, s.created_at
      FROM (
        SELECT 'portfolio'::text AS scope, t.portfolio_id AS scope_id, t.product_id, t.type,
               t.quantity, t.price, t.commission, t.date, t.created_at
          FROM fnz_transactions t
          JOIN fnz_portfolios p ON p.id = t.portfolio_id
         WHERE (p_user IS NULL OR p.user_id = p_user)
           AND p.gestione <> 'saldi'
        UNION ALL
        SELECT 'dossier'::text, t.dossier_id, t.product_id, t.type,
               t.quantity, t.price, t.commission, t.date, t.created_at
          FROM fnz_transactions t
         WHERE t.dossier_id IS NOT NULL
           AND (p_user IS NULL OR t.user_id = p_user)
      ) s
     ORDER BY s.scope, s.scope_id, s.product_id, s.date, s.created_at
  LOOP
    IF v_prod IS NOT NULL
       AND (r.scope, r.scope_id, r.product_id) IS DISTINCT FROM (v_scope, v_scope_id, v_prod) THEN
      IF v_qty > 0.000001 THEN
        v_out := v_out || jsonb_build_object('s', v_scope, 'i', v_scope_id, 'p', v_prod,
                                             'q', v_qty, 'c', v_cost, 'f', v_primo);
      END IF;
      v_qty := 0; v_cost := 0; v_prod := NULL;
    END IF;

    IF v_prod IS NULL THEN
      v_scope := r.scope; v_scope_id := r.scope_id; v_prod := r.product_id;
      v_primo := r.date::text || ' ' || r.created_at::text;
    END IF;

    IF r.type = 'BUY' THEN
      v_cost := v_cost + r.quantity * r.price + COALESCE(r.commission, 0);
      v_qty  := v_qty + r.quantity;
    ELSE
      IF v_qty > 0 THEN
        v_cost := v_cost - (v_cost / v_qty) * r.quantity;
      END IF;
      v_qty := v_qty - r.quantity;
    END IF;
  END LOOP;

  IF v_prod IS NOT NULL AND v_qty > 0.000001 THEN
    v_out := v_out || jsonb_build_object('s', v_scope, 'i', v_scope_id, 'p', v_prod,
                                         'q', v_qty, 'c', v_cost, 'f', v_primo);
  END IF;

  -- Portafogli a saldi: l'ultima quantità scritta per ogni moneta, costo NULL.
  SELECT v_out || COALESCE(jsonb_agg(jsonb_build_object(
           's', 'portfolio', 'i', u.portfolio_id, 'p', u.product_id,
           'q', u.qty, 'c', NULL, 'f', u.primo)), '[]'::jsonb)
    INTO v_out
    FROM (
      SELECT DISTINCT ON (sc.portfolio_id, sc.product_id)
             sc.portfolio_id, sc.product_id, sc.qty,
             (SELECT min(x.recorded_at)::text FROM fnz_saldi_crypto x
               WHERE x.portfolio_id = sc.portfolio_id AND x.product_id = sc.product_id) AS primo
        FROM fnz_saldi_crypto sc
        JOIN fnz_portfolios p ON p.id = sc.portfolio_id
       WHERE p.gestione = 'saldi'
         AND (p_user IS NULL OR p.user_id = p_user)
       ORDER BY sc.portfolio_id, sc.product_id, sc.recorded_at DESC, sc.created_at DESC
    ) u
   WHERE u.qty > 0.000001;

  RETURN QUERY
    SELECT e->>'s', (e->>'i')::uuid, (e->>'p')::uuid, (e->>'q')::numeric,
           (e->>'c')::numeric,  -- JSON null → NULL: il costo sconosciuto resta NULL
           (row_number() OVER (PARTITION BY e->>'s', e->>'i' ORDER BY e->>'f'))::integer
      FROM jsonb_array_elements(v_out) e
     ORDER BY 1, 2, 6;
END;
$$;

COMMENT ON FUNCTION public.fnz_posizioni(uuid) IS
  'Quantità e costo medio ponderato di ogni prodotto, per portafoglio e per dossier; per i portafogli con gestione = ''saldi'' l''ultima quantità di fnz_saldi_crypto, costo NULL. L''unica copia della regola: la leggono finanza.html, index.html, situazione-teresa.html, save-snapshot e l''APK nativa.';

REVOKE ALL ON FUNCTION public.fnz_posizioni(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.fnz_posizioni(uuid) TO authenticated, service_role;
