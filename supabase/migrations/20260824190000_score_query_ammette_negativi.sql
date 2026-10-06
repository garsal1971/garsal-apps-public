-- =====================================================================
-- Il totale di un'app può essere negativo: via i GREATEST(0, …)
--
-- Due `cm_apps.score_query` schiacciavano a zero il proprio totale:
--
--   SOS         GREATEST(0, COALESCE(SUM(points), 0))   (20260823100000)
--   Decisioni   GREATEST(0, COALESCE(SUM(l.points_earned), 0))  (20260614001500)
--
-- Ma i punti di quelle app **si possono perdere**: una risposta di SOS ha un
-- `points` anche negativo, e una decisione sbagliata pure. Con il clamp, da −40
-- a 0 la bolla mostrava lo stesso numero e il totale in home non si muoveva:
-- il malus non si vedeva da nessuna parte, cioè non esisteva. Un punteggio che
-- può solo salire non è un punteggio, è un contatore.
--
-- Tolto il clamp, la `score_query` dice quanto vale davvero l'app, segno
-- compreso. Il **saldo spendibile** resta invece a zero come minimo — quello
-- lo fa il client (`updateScorePanel()` e `HomeState.totaleNetto`, entrambi
-- `max(0, lordo − spesi)`): non si comprano premi con un debito, ma il debito
-- si vede.
--
-- ⚠️ Le altre `score_query` non stanno in nessuna migration (le righe di
-- `cm_apps` più vecchie sono state scritte a mano): se una di quelle ha lo
-- stesso clamp, si toglie dal pannello ⚙️ delle badge query in `index.html`.
-- =====================================================================

DO $$
BEGIN
  IF to_regclass('public.cm_apps') IS NULL THEN
    RAISE NOTICE 'cm_apps non esiste: nessuna score_query da aggiornare';
    RETURN;
  END IF;

  UPDATE cm_apps
     SET score_query = 'SELECT COALESCE(SUM(points), 0)::int FROM sos_sessions WHERE user_id = auth.uid() AND ended_at IS NOT NULL'
   WHERE html_file = 'sos.html'
     AND score_query LIKE '%GREATEST(0%';

  UPDATE cm_apps
     SET score_query = 'SELECT COALESCE(SUM(l.points_earned)::INTEGER, 0) FROM dc_logs l JOIN dc_decisions d ON d.id = l.decision_id WHERE d.user_id = auth.uid()'
   WHERE html_file = 'decisions.html'
     AND score_query LIKE '%GREATEST(0%';
END $$;
