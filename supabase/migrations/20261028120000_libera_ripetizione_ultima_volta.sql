-- ============================================================================
-- Libera ripetizione: «ultima volta» = quando la si è fatta davvero.
--
-- ob_action_complete_core e pv_action_complete_core scrivevano in
-- last_completed_date, per tutti i tipi, COALESCE(next_occurrence_date,
-- start_date, now()). Una libera ripetizione una prossima occorrenza non ce
-- l'ha, quindi ripiegava su start_date: «ultima: <giorno in cui è nata>» per
-- sempre, chiunque la completasse (Obiettivi, Piante, Auto-formazione).
--
-- ⚠️ Le due funzioni si correggono RISCRIVENDO IL CORPO VIVO con una regexp,
-- come 20261027100000_date_multiple_con_orario.sql: quel corpo non coincide
-- più col file in cui sono nate, e ridichiararle da capo butterebbe via le
-- modifiche fatte in mezzo. Gli altri tipi non cambiano: lì quella data è
-- l'occorrenza che si sta chiudendo, ed è giusto così.
--
-- Poi le righe già salvate: last_completed_date torna all'ultima esecuzione
-- riuscita in storico (solo per le libere ripetizioni che ne hanno una).
-- ============================================================================

DO $migr$
DECLARE
  v_fn  record;
  v_def text;
  v_new text;
  v_re  text := 'v_completed_date\s*:=\s*COALESCE\(v_act\.next_occurrence_date,\s*v_act\.start_date,\s*now\(\)\);';
BEGIN
  FOR v_fn IN
    SELECT p.oid, p.proname
      FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = 'public'
       AND p.proname IN ('ob_action_complete_core', 'pv_action_complete_core')
  LOOP
    v_def := pg_get_functiondef(v_fn.oid);
    v_new := regexp_replace(v_def, v_re,
               'v_completed_date := CASE WHEN v_act.type = ''free_repeat'' THEN now()
                       ELSE COALESCE(v_act.next_occurrence_date, v_act.start_date, now()) END;');
    IF v_new = v_def THEN
      RAISE EXCEPTION 'libera ripetizione: riga di v_completed_date non trovata in %', v_fn.proname;
    END IF;
    EXECUTE v_new;
    RAISE NOTICE 'aggiornata %', v_fn.proname;
  END LOOP;
END
$migr$;

-- Le date già scritte male.
UPDATE ob_actions a
   SET last_completed_date = h.ultima
  FROM (SELECT action_id, max("timestamp") AS ultima
          FROM ob_action_history
         WHERE action IN ('completed', 'completed_late')
         GROUP BY action_id) h
 WHERE h.action_id = a.id
   AND a.type = 'free_repeat';

UPDATE pv_actions a
   SET last_completed_date = h.ultima
  FROM (SELECT action_id, max("timestamp") AS ultima
          FROM pv_action_history
         WHERE action IN ('completed', 'completed_late')
         GROUP BY action_id) h
 WHERE h.action_id = a.id
   AND a.type = 'free_repeat';
