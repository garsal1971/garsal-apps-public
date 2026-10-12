-- Un portafoglio a saldi (il CRYPTO) non ha liquidità.
--
-- Tutto quel che il fondo collegato raccoglie viene convertito in crypto, e le quantità si
-- scrivono a mano in `fnz_saldi_crypto` (`20261012100000_fnz_saldi_crypto.sql`). Il conto di
-- `fnz_liquidita` — versato nel fondo − speso in acquisti — lì non vale più: le transazioni non
-- si registrano, quindi ogni versamento nuovo comparirebbe come liquidità che non c'è, e il
-- valore del portafoglio la conterebbe in più rispetto alle monete.
--
-- Identica alla versione di `20260924140000_fnz_posizioni_liquidita.sql` per tutti gli altri
-- portafogli; per quelli a saldi `cash` è 0. `speso` e `versato` restano quel che erano.
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
    SELECT p.id, p.gestione FROM fnz_portfolios p WHERE p_user IS NULL OR p.user_id = p_user
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
         CASE WHEN pf.gestione = 'saldi' OR v.versato IS NULL THEN 0
              ELSE GREATEST(0, v.versato - COALESCE(s.speso, 0)) END
    FROM pf
    LEFT JOIN spesa s      ON s.portfolio_id = pf.id
    LEFT JOIN versamenti v ON v.portfolio_id = pf.id
$$;

COMMENT ON FUNCTION public.fnz_liquidita(uuid) IS
  'Liquidità di ogni portafoglio: versato nominale del fondo collegato − speso netto, mai sotto zero; 0 sui portafogli a saldi. L''unica copia della regola.';

REVOKE ALL ON FUNCTION public.fnz_liquidita(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.fnz_liquidita(uuid) TO authenticated, service_role;
