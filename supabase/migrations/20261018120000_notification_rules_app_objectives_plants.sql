-- ============================================================================
-- Promemoria di Obiettivi e Piante: il vincolo su `app` non li ammetteva
-- ============================================================================
-- cm_notification_rules_app_check (20260705140000) elencava solo tasks, habits,
-- events, weight, quick, ta_firi: le regole con app = 'objectives' o 'plants'
-- venivano rifiutate (23514) e i promemoria delle azioni non si salvavano.
--
-- Il vincolo si ricrea con i valori noti PIÙ quelli già presenti in tabella:
-- se in produzione era stato allargato a mano non resta fuori niente, e l'ADD
-- non fallisce su righe esistenti. Stessa cosa su cm_notification_queue, se lì
-- esiste un vincolo analogo.
-- ============================================================================
DO $$
DECLARE v_tab text; v_con text; v_vals text;
BEGIN
  FOREACH v_tab IN ARRAY ARRAY['cm_notification_rules', 'cm_notification_queue'] LOOP
    v_con := v_tab || '_app_check';
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = v_con) THEN
      EXECUTE format('ALTER TABLE %I DROP CONSTRAINT %I', v_tab, v_con);
      EXECUTE format($q$SELECT string_agg(quote_literal(a), ',' ORDER BY a) FROM (
          SELECT unnest(ARRAY['tasks','habits','events','weight','quick','ta_firi',
                              'cost_analysis','plants','objectives']) AS a
          UNION SELECT DISTINCT app FROM %I WHERE app IS NOT NULL) s$q$, v_tab) INTO v_vals;
      EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I CHECK (app = ANY (ARRAY[%s]::text[]))',
                     v_tab, v_con, v_vals);
      RAISE NOTICE '% ricreato: %', v_con, v_vals;
    END IF;
  END LOOP;
END $$;
