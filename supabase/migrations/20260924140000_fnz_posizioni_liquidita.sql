-- Posizioni e liquidità dei portafogli: UNA regola, nel database.
--
-- Fino a qui la stessa regola viveva in quattro copie da tenere uguali a mano:
-- `computeHoldings` / `computePortfolioCash` in finanza.html, le gemelle in
-- TypeScript della Edge Function `save-snapshot`, `fetchPortfolioLiveValue` in
-- index.html e `pfHoldings` / `pfCash` in situazione-teresa.html. Cambiarne una
-- sola faceva dire alla dashboard, allo snapshot notturno, all'avviso in home e
-- alla pagina di Teresa quattro numeri diversi sullo stesso conto.
--
-- ⚠️ Scende nel database la parte SEQUENZIALE, cioè quella che si sbaglia:
-- quantità e costo medio ponderato (le vendite tolgono costo al costo medio del
-- momento), e la liquidità (versato nel fondo collegato − acquisti con
-- commissioni + vendite al netto, mai sotto zero). Il VALORE — quantità × prezzo
-- — resta nei client con la loro fonte di prezzi di sempre: spostare anche
-- quella avrebbe cambiato di qualche euro i numeri rispetto agli snapshot già
-- salvati, cioè una serie storica che non si confronta più con sé stessa.
--
-- ⚠️ SECURITY INVOKER: chi chiama vede quel che la RLS gli lascia vedere —
-- Salvatore le sue righe, Teresa il solo Conto Risparmio (vedi
-- `20260905120000_guest_teresa_portafoglio_conto_risparmio.sql`). La Edge
-- Function gira col service role, dove la RLS non vale: per questo c'è
-- `p_user`, che restringe alle righe di un utente. NULL = «quel che vedo».

-- ── Posizioni ────────────────────────────────────────────────────────
--
-- Una riga per (ambito, prodotto) con quantità > 0: `scope` vale 'portfolio'
-- (per portafoglio) o 'dossier' (per dossier titoli, trasversale ai
-- portafogli). `ordine` mette i prodotti nell'ordine in cui sono comparsi — la
-- prima transazione — che è l'ordine in cui la pagina li ha sempre elencati.
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
         WHERE p_user IS NULL OR p.user_id = p_user
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
      -- La prima transazione del gruppo è la più vecchia: l'ordine è per data.
      v_primo := r.date::text || ' ' || r.created_at::text;
    END IF;

    IF r.type = 'BUY' THEN
      v_cost := v_cost + r.quantity * r.price + COALESCE(r.commission, 0);
      v_qty  := v_qty + r.quantity;
    ELSE
      -- Una vendita toglie costo al costo MEDIO del momento, non a quello finale.
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

  RETURN QUERY
    SELECT e->>'s', (e->>'i')::uuid, (e->>'p')::uuid, (e->>'q')::numeric, (e->>'c')::numeric,
           (row_number() OVER (PARTITION BY e->>'s', e->>'i' ORDER BY e->>'f'))::integer
      FROM jsonb_array_elements(v_out) e
     ORDER BY 1, 2, 6;
END;
$$;

COMMENT ON FUNCTION public.fnz_posizioni(uuid) IS
  'Quantità e costo medio ponderato di ogni prodotto, per portafoglio e per dossier. L''unica copia della regola: la leggono finanza.html, index.html, situazione-teresa.html e save-snapshot.';

-- ── Liquidità ────────────────────────────────────────────────────────
--
-- Una riga per portafoglio. `speso` = acquisti con commissioni − vendite al
-- netto (il vecchio `portfolioNetSpent`); `versato` = i versamenti NOMINALI
-- che fanno quota (`auto`/`confermato`, NULL vale `confermato`) del fondo
-- collegato — NULL se il portafoglio un fondo non ce l'ha; `cash` =
-- max(0, versato − speso), zero senza fondo.
--
-- ⚠️ Nominale e non rivalutato FOI: la rivalutazione divide le quote fra i
-- partecipanti, non è denaro sul conto. ⚠️ Con più fondi sullo stesso
-- portafoglio vale il PRIMO nato, come il `find` sull'elenco ordinato per
-- `created_at` che faceva finanza.html.
CREATE OR REPLACE FUNCTION public.fnz_liquidita(p_user uuid DEFAULT NULL)
RETURNS TABLE (
  portfolio_id uuid,
  speso        numeric,
  versato      numeric,
  cash         numeric
)
LANGUAGE sql STABLE SECURITY INVOKER SET search_path = public
AS $$
  WITH pf AS (
    SELECT p.id FROM fnz_portfolios p WHERE p_user IS NULL OR p.user_id = p_user
  ),
  spesa AS (
    SELECT t.portfolio_id,
           SUM(CASE WHEN t.type = 'BUY'
                    THEN t.quantity * t.price + COALESCE(t.commission, 0)
                    ELSE -(t.quantity * t.price - COALESCE(t.commission, 0)) END) AS speso
      FROM fnz_transactions t
     WHERE t.portfolio_id IN (SELECT id FROM pf)
     GROUP BY t.portfolio_id
  ),
  fondo AS (
    SELECT DISTINCT ON (f.linked_portfolio_id) f.linked_portfolio_id AS portfolio_id, f.id
      FROM fnz_funds f
     WHERE f.linked_portfolio_id IN (SELECT id FROM pf)
     ORDER BY f.linked_portfolio_id, f.created_at, f.id
  ),
  versamenti AS (
    SELECT fo.portfolio_id, COALESCE(SUM(c.amount), 0) AS versato
      FROM fondo fo
      LEFT JOIN fnz_fund_contributions c
        ON c.fund_id = fo.id
       AND COALESCE(c.status, 'confermato') IN ('auto', 'confermato')
     GROUP BY fo.portfolio_id
  )
  SELECT pf.id,
         COALESCE(s.speso, 0),
         v.versato,
         CASE WHEN v.versato IS NULL THEN 0
              ELSE GREATEST(0, v.versato - COALESCE(s.speso, 0)) END
    FROM pf
    LEFT JOIN spesa s      ON s.portfolio_id = pf.id
    LEFT JOIN versamenti v ON v.portfolio_id = pf.id
$$;

COMMENT ON FUNCTION public.fnz_liquidita(uuid) IS
  'Liquidità di ogni portafoglio: versato nominale del fondo collegato − speso netto, mai sotto zero. L''unica copia della regola.';

-- ⚠️ p_user restringe e non allarga: con la RLS attiva un utente vede comunque
-- solo le righe che le policy gli danno. Il service role (save-snapshot) passa
-- p_user perché lì la RLS non vale.
REVOKE ALL ON FUNCTION public.fnz_posizioni(uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.fnz_liquidita(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.fnz_posizioni(uuid) TO authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.fnz_liquidita(uuid) TO authenticated, service_role;
