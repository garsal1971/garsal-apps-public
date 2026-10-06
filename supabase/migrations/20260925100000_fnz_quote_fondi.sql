-- Le quote dei fondi comuni: UNA regola, nel database.
--
-- Fino a qui la stessa regola viveva in due copie da tenere uguali a mano:
-- `computeFundShares` in finanza.html e `pfQuote` in situazione-teresa.html,
-- che la porta per la vista 📈 Portafoglio. Cambiarne una sola faceva dire alle
-- due pagine due percentuali diverse sullo stesso fondo.
--
-- La regola, riga per riga quella del JavaScript:
--   • contano i soli movimenti 'auto' e 'confermato' (NULL vale 'confermato');
--   • l'anno di riferimento è il primo di quei movimenti;
--   • un movimento si rivaluta per indice FOI dell'anno di riferimento / indice
--     del suo anno; senza uno dei due indici la rivalutazione non c'è (NULL) e
--     `has_missing_index` lo dice — meglio nessun valore che uno sbagliato;
--   • si raggruppa per partecipante in anagrafica, o per nome scritto a mano
--     (`name:<NOME MAIUSCOLO>`) sui fondi nati prima dell'anagrafica;
--   • somma ALGEBRICA: un prelievo è negativo e abbassa la quota di chi l'ha fatto;
--   • percentuale = rivalutato del partecipante / rivalutato del fondo.
--
-- ⚠️ L'indice FOI è quello del PROPRIETARIO del fondo, come in
-- `fnz_fund_recalc_adjusted`: la pagina di Teresa legge `fnz_foi_index` per
-- intero, e con più utenti un anno avrebbe più righe.
--
-- ⚠️ SECURITY INVOKER: chi chiama vede quel che la RLS gli lascia vedere —
-- Salvatore i suoi fondi, Teresa il solo fondo del Conto Risparmio.
-- `p_fund_id` NULL = tutti i fondi visibili.
CREATE OR REPLACE FUNCTION public.fnz_quote_fondi(p_fund_id uuid DEFAULT NULL)
RETURNS TABLE (
  fund_id           uuid,
  reference_year    integer,
  total_nominal     numeric,
  total_adjusted    numeric,
  has_missing_index boolean,
  coefficienti      jsonb,
  participants      jsonb
)
LANGUAGE sql STABLE SECURITY INVOKER SET search_path = public
AS $$
  WITH fondi AS (
    SELECT f.id, f.user_id FROM fnz_funds f
     WHERE p_fund_id IS NULL OR f.id = p_fund_id
  ),
  mov AS (
    SELECT c.fund_id, c.participant_id, c.participant,
           c.amount::numeric AS amount, c.date, c.created_at,
           extract(year FROM c.date)::int AS anno,
           COALESCE(c.participant_id::text,
                    'name:' || upper(btrim(COALESCE(c.participant, '')))) AS chiave
      FROM fnz_fund_contributions c
      JOIN fondi f ON f.id = c.fund_id
     WHERE COALESCE(c.status, 'confermato') IN ('auto', 'confermato')
  ),
  rif AS (
    SELECT f.id AS fund_id, min(m.anno) AS anno_rif
      FROM fondi f JOIN mov m ON m.fund_id = f.id
     GROUP BY f.id
  ),
  -- Il coefficiente di OGNI anno che compare fra i movimenti del fondo, anche
  -- quelli che non contano: la pagina li elenca tutti, col loro coefficiente.
  anni AS (
    SELECT DISTINCT c.fund_id, extract(year FROM c.date)::int AS anno
      FROM fnz_fund_contributions c JOIN fondi f ON f.id = c.fund_id
  ),
  coef AS (
    SELECT a.fund_id, a.anno,
           CASE WHEN ir.index_value IS NULL OR ia.index_value IS NULL THEN NULL
                ELSE ir.index_value::numeric / ia.index_value::numeric END AS c
      FROM anni a
      JOIN rif r    ON r.fund_id = a.fund_id
      JOIN fondi f  ON f.id = a.fund_id
      LEFT JOIN fnz_foi_index ir ON ir.user_id = f.user_id AND ir.year = r.anno_rif
      LEFT JOIN fnz_foi_index ia ON ia.user_id = f.user_id AND ia.year = a.anno
  ),
  righe AS (
    SELECT m.*, k.c, m.amount * k.c AS adjusted
      FROM mov m JOIN coef k ON k.fund_id = m.fund_id AND k.anno = m.anno
  ),
  per_part AS (
    SELECT fund_id, chiave,
           -- Il primo movimento del gruppo dà id e nome, come nel JavaScript.
           (array_agg(participant_id ORDER BY date, created_at))[1] AS participant_id,
           (array_agg(participant    ORDER BY date, created_at))[1] AS participant,
           count(*)::int                              AS cnt,
           count(*) FILTER (WHERE amount >= 0)::int   AS deposits,
           count(*) FILTER (WHERE amount < 0)::int    AS withdrawals,
           sum(amount)                                AS tot_nom,
           COALESCE(sum(adjusted), 0)                 AS tot_adj,
           bool_or(adjusted IS NULL)                  AS manca
      FROM righe
     GROUP BY fund_id, chiave
  ),
  tot AS (
    SELECT fund_id, sum(amount) AS tot_nom, COALESCE(sum(adjusted), 0) AS tot_adj,
           bool_or(adjusted IS NULL) AS manca
      FROM righe GROUP BY fund_id
  )
  SELECT r.fund_id, r.anno_rif, t.tot_nom, t.tot_adj, t.manca,
         (SELECT jsonb_object_agg(k.anno::text, k.c) FROM coef k WHERE k.fund_id = r.fund_id),
         (SELECT jsonb_agg(jsonb_build_object(
                   'key', p.chiave, 'participant_id', p.participant_id,
                   'participant', p.participant, 'count', p.cnt,
                   'deposits', p.deposits, 'withdrawals', p.withdrawals,
                   'total_nominal', p.tot_nom, 'total_adjusted', p.tot_adj,
                   'has_missing_index', p.manca,
                   'percentage', CASE WHEN t.tot_adj <> 0 THEN p.tot_adj / t.tot_adj * 100 ELSE 0 END)
                 ORDER BY p.tot_adj DESC)
            FROM per_part p WHERE p.fund_id = r.fund_id)
    FROM rif r JOIN tot t ON t.fund_id = r.fund_id;
$$;

REVOKE ALL ON FUNCTION public.fnz_quote_fondi(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.fnz_quote_fondi(uuid) TO authenticated, service_role;
