-- ============================================================================
-- Date multiple con l'ORARIO di ciascuna data (Tasks, Obiettivi, Piante)
-- ============================================================================
-- Fino a qui multiple_dates (text[]) portava le sole date 'YYYY-MM-DD' e l'ora
-- era una per tutte, quella di start_date. Ora ogni elemento può portare anche
-- l'ora di inizio e, facoltativa, quella di fine:
--
--   'YYYY-MM-DD'                 la regola di sempre: ora di start_date
--   'YYYY-MM-DD HH:MM'           con l'ora di inizio
--   'YYYY-MM-DD HH:MM-HH:MM'     con inizio e fine (la fine è solo informativa)
--
-- ⚠️ Nessuna colonna nuova e nessun backfill: le righe di prima restano valide
-- così come sono. L'ordinamento come stringa resta quello cronologico.
--
-- ⚠️ Il FUSO non è lo stesso nelle tre app, e non è una svista:
--   Tasks scrive gli orari «già locali» senza fuso (Postgres li legge come UTC),
--   Obiettivi e Piante li scrivono con toISOString(), cioè in UTC vero.
-- Quindi l'ora di un elemento si legge in 'UTC' per i task e in 'Europe/Rome'
-- per azioni di obiettivi e piante: è l'unico modo perché tornino quelle scritte.
--
-- ⚠️ La prossima occorrenza non si cerca più per UGUAGLIANZA fra la data in
-- corso e un elemento (che con l'ora dentro non regge più), ma è il primo
-- elemento il cui istante viene DOPO quello in corso. Sull'elenco ordinato è
-- lo stesso risultato di prima.
--
-- Le RPC si correggono riscrivendo il loro testo attuale (regexp sul corpo
-- vivo) invece di ricopiarle per intero: sono otto funzioni lunghe, e una copia
-- a mano rischierebbe di riportare indietro qualcosa cambiato nel frattempo.
-- Ogni sostituzione che non trova il suo pezzo FA FALLIRE la migration.
-- ============================================================================

-- L'istante di un elemento di multiple_dates.
CREATE OR REPLACE FUNCTION public.md_ts(p_val text, p_tod interval, p_tz text)
RETURNS timestamptz
LANGUAGE sql STABLE          -- date::timestamptz dipende dal TimeZone della sessione
SET search_path TO 'public'
AS $$
  SELECT CASE
    WHEN btrim(p_val) ~ '^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}'
      THEN ((left(btrim(p_val), 10) || ' ' || substr(btrim(p_val), 12, 5))::timestamp
            AT TIME ZONE p_tz)
    ELSE left(btrim(p_val), 10)::date::timestamptz + COALESCE(p_tod, interval '0')
  END
$$;

-- La prossima occorrenza dopo p_cur: il primo elemento il cui istante è dopo.
-- I 59 secondi assorbono i secondi che un client può aver scritto in più.
CREATE OR REPLACE FUNCTION public.md_prossima(p_dates text[], p_cur timestamptz,
                                              p_tod interval, p_tz text)
RETURNS timestamptz
LANGUAGE sql STABLE
SET search_path TO 'public'
AS $$
  SELECT min(md_ts(d, p_tod, p_tz))
    FROM unnest(p_dates) AS d
   WHERE p_cur IS NOT NULL
     AND d IS NOT NULL AND btrim(d) <> ''
     AND md_ts(d, p_tod, p_tz) > p_cur + interval '59 seconds'
$$;

DO $migr$
DECLARE
  v_fn   record;
  v_def  text;
  v_new  text;
  v_tz   text;
  v_re   text := 'v_cur_str := COALESCE\((v_\w+)\.next_occurrence_date::date::text, ''''\);.*?v_next_ts := v_dates\[v_cur_idx \+ 1\]::date::timestamptz \+ v_time_of_day;\s*END IF;';
BEGIN
  FOR v_fn IN
    SELECT p.oid, p.proname
      FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = 'public'
       AND p.proname IN ('task_complete', 'task_skip', 'task_fail',
                         'ob_action_complete_core', 'ob_action_skip_core', 'ob_action_fail',
                         'pv_action_complete_core', 'pv_action_skip_core')
  LOOP
    v_tz  := CASE WHEN v_fn.proname LIKE 'task_%' THEN 'UTC' ELSE 'Europe/Rome' END;
    v_def := pg_get_functiondef(v_fn.oid);
    v_new := regexp_replace(v_def, v_re,
               'v_next_ts := md_prossima(v_dates, \1.next_occurrence_date, v_time_of_day, ''' || v_tz || ''');');
    IF v_new = v_def THEN
      RAISE EXCEPTION 'date multiple con orario: blocco «multiple» non trovato in %', v_fn.proname;
    END IF;
    EXECUTE v_new;
    RAISE NOTICE 'aggiornata %', v_fn.proname;
  END LOOP;

  -- ↩️ ob_action_undo: tornando su un'azione multipla, l'ora è quella della sua data.
  SELECT p.oid INTO v_fn
    FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
   WHERE n.nspname = 'public' AND p.proname = 'ob_action_undo';
  IF FOUND THEN
    v_def := pg_get_functiondef(v_fn.oid);
    v_new := replace(v_def,
      'v_next_ts := v_h.occurrence_date::timestamptz + v_time_of_day;',
      'v_next_ts := COALESCE(
      CASE WHEN v_act.type = ''multiple'' THEN
        (SELECT md_ts(d, v_time_of_day, ''Europe/Rome'')
           FROM unnest(v_act.multiple_dates) AS d
          WHERE md_ts(d, v_time_of_day, ''Europe/Rome'')::date = v_h.occurrence_date
          ORDER BY d LIMIT 1)
      END,
      v_h.occurrence_date::timestamptz + v_time_of_day);');
    IF v_new = v_def THEN
      RAISE EXCEPTION 'date multiple con orario: riga da sostituire non trovata in ob_action_undo';
    END IF;
    EXECUTE v_new;
  END IF;
END
$migr$;
