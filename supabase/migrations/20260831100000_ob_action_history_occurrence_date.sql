-- ============================================================================
-- ob_action_history.occurrence_date — il giorno per cui l'azione era in calendario
--
-- Lo storico diceva **quando** un'azione è stata chiusa (`timestamp`) e non
-- **per quando** era programmata: le due cose coincidono solo se la si chiude il
-- giorno stesso, e su un'occorrenza arretrata chiusa tre settimane dopo la riga
-- non lasciava capire che era in ritardo. Il ritardo si vedeva nell'etichetta
-- solo per una `single` con scadenza (`completed_late`); per tutto il resto non
-- si vedeva affatto.
--
-- ⚠️ La data si legge PRIMA che la RPC sposti `next_occurrence_date`: subito
-- dopo, la riga è già sulla volta successiva e quella che si stava chiudendo non
-- è più leggibile da nessuna parte. È la stessa ragione per cui `occorrenzaDi()`
-- in `obiettivi.html` la legge prima di chiamare la RPC.
--
-- ⚠️ Niente backfill sulle righe già in archivio: la data che avevano non è più
-- ricostruibile — la prossima occorrenza si è spostata da un pezzo — e riempirla
-- col `timestamp` scriverebbe «programmata per il giorno in cui è stata chiusa»,
-- cioè che sono state tutte puntuali. Restano NULL e la pagina mostra un
-- trattino: un dato che non c'è si vede, un dato inventato no.
--
-- ⚠️ Una libera ripetizione resta NULL anche d'ora in poi: non ha una prossima
-- volta, quindi non ha un giorno programmato — `start_date` lì è quando è nata,
-- non quando era in calendario.
-- ============================================================================

ALTER TABLE ob_action_history
  ADD COLUMN IF NOT EXISTS occurrence_date date;

COMMENT ON COLUMN ob_action_history.occurrence_date IS
  'Il giorno per cui l''azione era in calendario, letto prima che la RPC sposti la prossima occorrenza. NULL sulle righe scritte prima di questa colonna e su ogni libera ripetizione.';

-- ob_action_complete — invariata, salvo la colonna in più su ogni INSERT
CREATE OR REPLACE FUNCTION ob_action_complete(
  p_action_id uuid,
  p_today     date DEFAULT CURRENT_DATE
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_act            ob_actions%ROWTYPE;
  v_from_status    text;
  v_points         integer;
  v_action         text := 'completed';
  v_next_date      date;
  v_next_ts        timestamptz;
  v_completed_date timestamptz;
  v_time_of_day    interval;

  v_all_completed  boolean;
  v_all_done       boolean;
  v_wf_pts         jsonb;

  v_dates    text[];
  v_cur_str  text;
  v_cur_idx  integer := NULL;
  j          integer;
  v_occ_date date;
BEGIN
  SELECT * INTO v_act FROM ob_actions WHERE id = p_action_id AND user_id = auth.uid();
  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'error', 'azione non trovata');
  END IF;

  -- Il giorno per cui l'azione era in calendario, letto PRIMA che la prossima
  -- occorrenza venga spostata. Una libera ripetizione non ne ha uno — si fa
  -- quando capita — e resta NULL invece di prendersi `start_date`, che sarebbe
  -- una data programmata inventata.
  v_occ_date := CASE WHEN v_act.type = 'free_repeat' THEN NULL
                     ELSE COALESCE(v_act.next_occurrence_date, v_act.start_date)::date END;

  v_from_status    := v_act.status;
  v_points         := COALESCE(v_act.success_points, 0);
  v_completed_date := COALESCE(v_act.next_occurrence_date, v_act.start_date, now());

  -- L'orario originale si preserva: un'azione delle 9:00 resta alle 9:00 a ogni
  -- occorrenza successiva.
  v_time_of_day := COALESCE(v_act.start_date, now())
                   - date_trunc('day', COALESCE(v_act.start_date, now()));

  -- ── workflow ────────────────────────────────────────────────────────────
  IF v_act.type = 'workflow' THEN
    IF v_act.workflow_steps IS NULL OR jsonb_array_length(v_act.workflow_steps) = 0 THEN
      RETURN jsonb_build_object('ok', false, 'error', 'workflow senza step');
    END IF;

    SELECT bool_and((s->>'status') = 'completed'),
           bool_and((s->>'status') IN ('completed', 'failed'))
      INTO v_all_completed, v_all_done
      FROM jsonb_array_elements(v_act.workflow_steps) AS s;

    IF NOT v_all_done THEN
      RETURN jsonb_build_object('ok', true, 'action', 'step_completed', 'type', 'workflow');
    END IF;

    IF NOT v_all_completed THEN
      RETURN jsonb_build_object('ok', true, 'action', 'partial', 'type', 'workflow');
    END IF;

    v_wf_pts := COALESCE(v_act.workflow_points, '{}'::jsonb);
    v_points := COALESCE((v_wf_pts->>'task_success')::integer, 0);

    INSERT INTO ob_action_history (user_id, action_id, objective_id, action_title,
                                   from_status, to_status, action, points, occurrence_date)
    VALUES (v_act.user_id, p_action_id, v_act.objective_id, v_act.title,
            v_from_status, 'terminated', 'completed', v_points, v_occ_date);

    UPDATE ob_actions
       SET status = 'terminated', last_completed_date = now()
     WHERE id = p_action_id;

    RETURN jsonb_build_object('ok', true, 'action', 'completed', 'points', v_points, 'type', 'workflow');
  END IF;

  -- Una singola completata dopo la scadenza vale i punti "in ritardo".
  IF v_act.type = 'single' AND v_act.deadline IS NOT NULL AND p_today > v_act.deadline THEN
    v_points := COALESCE(v_act.late_points, 0);
    v_action := 'completed_late';
  END IF;

  INSERT INTO ob_action_history (user_id, action_id, objective_id, action_title,
                                 from_status, to_status, action, points, occurrence_date)
  VALUES (v_act.user_id, p_action_id, v_act.objective_id, v_act.title,
          v_from_status, 'completed', v_action, v_points, v_occ_date);

  IF v_act.type = 'single' THEN
    UPDATE ob_actions
       SET status = 'terminated', last_completed_date = v_completed_date
     WHERE id = p_action_id;

    INSERT INTO ob_action_history (user_id, action_id, objective_id, action_title,
                                   from_status, to_status, action, points, occurrence_date)
    VALUES (v_act.user_id, p_action_id, v_act.objective_id, v_act.title,
            'completed', 'terminated', 'terminated', 0, v_occ_date);

  ELSIF v_act.type = 'simple_recurring' THEN
    v_next_ts := COALESCE(v_act.next_occurrence_date, v_act.start_date, now())
                 + (COALESCE(v_act.repeat_after_days, 7) || ' days')::interval;

    UPDATE ob_actions
       SET status = 'completed', last_completed_date = v_completed_date,
           next_occurrence_date = v_next_ts
     WHERE id = p_action_id;

  ELSIF v_act.type = 'recurring' THEN
    v_next_date := ob_action_next_recurring_date(
      v_act, COALESCE(v_act.next_occurrence_date, v_act.start_date)::date);

    IF v_next_date IS NOT NULL THEN
      v_next_ts := v_next_date::timestamptz + v_time_of_day;
    END IF;

    UPDATE ob_actions
       SET status = CASE WHEN v_next_date IS NULL THEN 'terminated' ELSE 'completed' END,
           last_completed_date = v_completed_date,
           next_occurrence_date = v_next_ts
     WHERE id = p_action_id;

    IF v_next_date IS NULL THEN
      INSERT INTO ob_action_history (user_id, action_id, objective_id, action_title,
                                     from_status, to_status, action, points, occurrence_date)
      VALUES (v_act.user_id, p_action_id, v_act.objective_id, v_act.title,
              'completed', 'terminated', 'terminated', 0, v_occ_date);
    END IF;

  ELSIF v_act.type = 'multiple' THEN
    SELECT array_agg(d ORDER BY d) INTO v_dates FROM unnest(v_act.multiple_dates) AS d;

    -- ⚠️ ::date::text dà 'YYYY-MM-DD', confrontabile con multiple_dates[].
    -- split_part(timestamptz::text,'T',1) non funziona: Postgres separa data e
    -- ora con uno spazio, non con 'T', e il confronto fallirebbe sempre —
    -- l'azione verrebbe terminata alla prima occorrenza.
    v_cur_str := COALESCE(v_act.next_occurrence_date::date::text, '');

    FOR j IN 1..COALESCE(array_length(v_dates, 1), 0) LOOP
      IF v_dates[j] = v_cur_str THEN
        v_cur_idx := j;
        EXIT;
      END IF;
    END LOOP;

    IF v_cur_idx IS NOT NULL AND v_cur_idx < array_length(v_dates, 1) THEN
      v_next_ts := v_dates[v_cur_idx + 1]::date::timestamptz + v_time_of_day;
    END IF;

    UPDATE ob_actions
       SET status = CASE WHEN v_next_ts IS NULL THEN 'terminated' ELSE 'completed' END,
           last_completed_date = v_completed_date,
           next_occurrence_date = v_next_ts
     WHERE id = p_action_id;

    IF v_next_ts IS NULL THEN
      INSERT INTO ob_action_history (user_id, action_id, objective_id, action_title,
                                     from_status, to_status, action, points, occurrence_date)
      VALUES (v_act.user_id, p_action_id, v_act.objective_id, v_act.title,
              'completed', 'terminated', 'terminated', 0, v_occ_date);
    END IF;

  ELSE -- free_repeat
    UPDATE ob_actions
       SET status = 'completed', last_completed_date = v_completed_date
     WHERE id = p_action_id;
  END IF;

  RETURN jsonb_build_object('ok', true, 'action', v_action, 'points', v_points,
                            'type', v_act.type, 'next', v_next_ts);
END;
$$;
-- ---------------------------------------------------------------------------
-- ob_action_skip
CREATE OR REPLACE FUNCTION ob_action_skip(
  p_action_id uuid,
  p_days      integer DEFAULT 1
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_act         ob_actions%ROWTYPE;
  v_from_status text;
  v_points      integer;
  v_next_date   date;
  v_next_ts     timestamptz;
  v_time_of_day interval;
  v_dates       text[];
  v_cur_str     text;
  v_cur_idx     integer := NULL;
  j             integer;
  v_occ_date date;
BEGIN
  SELECT * INTO v_act FROM ob_actions WHERE id = p_action_id AND user_id = auth.uid();
  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'error', 'azione non trovata');
  END IF;

  -- Il giorno per cui l'azione era in calendario, letto PRIMA che la prossima
  -- occorrenza venga spostata. Una libera ripetizione non ne ha uno — si fa
  -- quando capita — e resta NULL invece di prendersi `start_date`, che sarebbe
  -- una data programmata inventata.
  v_occ_date := CASE WHEN v_act.type = 'free_repeat' THEN NULL
                     ELSE COALESCE(v_act.next_occurrence_date, v_act.start_date)::date END;

  IF v_act.type = 'free_repeat' OR v_act.type = 'workflow' THEN
    RETURN jsonb_build_object('ok', false, 'error', 'questo tipo non si può saltare');
  END IF;

  v_from_status := v_act.status;
  v_points      := COALESCE(v_act.skip_points, 0);
  v_time_of_day := COALESCE(v_act.start_date, now())
                   - date_trunc('day', COALESCE(v_act.start_date, now()));

  INSERT INTO ob_action_history (user_id, action_id, objective_id, action_title,
                                 from_status, to_status, action, points, occurrence_date)
  VALUES (v_act.user_id, p_action_id, v_act.objective_id, v_act.title,
          v_from_status, 'skipped', 'skipped', v_points, v_occ_date);

  IF v_act.type = 'single' THEN
    v_next_ts := COALESCE(v_act.next_occurrence_date, v_act.start_date, now())
                 + (p_days || ' days')::interval;
    UPDATE ob_actions SET status = 'skipped', next_occurrence_date = v_next_ts
     WHERE id = p_action_id;

  ELSIF v_act.type = 'simple_recurring' THEN
    v_next_ts := COALESCE(v_act.next_occurrence_date, v_act.start_date, now())
                 + (COALESCE(v_act.repeat_after_days, 7) || ' days')::interval;
    UPDATE ob_actions SET status = 'skipped', next_occurrence_date = v_next_ts
     WHERE id = p_action_id;

  ELSIF v_act.type = 'recurring' THEN
    v_next_date := ob_action_next_recurring_date(
      v_act, COALESCE(v_act.next_occurrence_date, v_act.start_date)::date);

    IF v_next_date IS NULL THEN
      RETURN jsonb_build_object('ok', false, 'error', 'impossibile calcolare la prossima occorrenza');
    END IF;

    v_next_ts := v_next_date::timestamptz + v_time_of_day;
    UPDATE ob_actions SET status = 'skipped', next_occurrence_date = v_next_ts
     WHERE id = p_action_id;

  ELSE -- multiple
    SELECT array_agg(d ORDER BY d) INTO v_dates FROM unnest(v_act.multiple_dates) AS d;
    v_cur_str := COALESCE(v_act.next_occurrence_date::date::text, '');

    FOR j IN 1..COALESCE(array_length(v_dates, 1), 0) LOOP
      IF v_dates[j] = v_cur_str THEN
        v_cur_idx := j;
        EXIT;
      END IF;
    END LOOP;

    IF v_cur_idx IS NOT NULL AND v_cur_idx < array_length(v_dates, 1) THEN
      v_next_ts := v_dates[v_cur_idx + 1]::date::timestamptz + v_time_of_day;
    END IF;

    UPDATE ob_actions
       SET status = CASE WHEN v_next_ts IS NULL THEN 'terminated' ELSE 'skipped' END,
           next_occurrence_date = v_next_ts
     WHERE id = p_action_id;

    IF v_next_ts IS NULL THEN
      INSERT INTO ob_action_history (user_id, action_id, objective_id, action_title,
                                     from_status, to_status, action, points, occurrence_date)
      VALUES (v_act.user_id, p_action_id, v_act.objective_id, v_act.title,
              'skipped', 'terminated', 'terminated', 0, v_occ_date);
    END IF;
  END IF;

  RETURN jsonb_build_object('ok', true, 'action', 'skipped', 'points', v_points,
                            'type', v_act.type, 'next', v_next_ts);
END;
$$;
-- ---------------------------------------------------------------------------
-- ob_action_fail
CREATE OR REPLACE FUNCTION ob_action_fail(p_action_id uuid)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_act         ob_actions%ROWTYPE;
  v_from_status text;
  v_points      integer;
  v_next_date   date;
  v_next_ts     timestamptz;
  v_time_of_day interval;
  v_dates       text[];
  v_cur_str     text;
  v_cur_idx     integer := NULL;
  j             integer;
  v_occ_date date;
BEGIN
  SELECT * INTO v_act FROM ob_actions WHERE id = p_action_id AND user_id = auth.uid();
  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'error', 'azione non trovata');
  END IF;

  -- Il giorno per cui l'azione era in calendario, letto PRIMA che la prossima
  -- occorrenza venga spostata. Una libera ripetizione non ne ha uno — si fa
  -- quando capita — e resta NULL invece di prendersi `start_date`, che sarebbe
  -- una data programmata inventata.
  v_occ_date := CASE WHEN v_act.type = 'free_repeat' THEN NULL
                     ELSE COALESCE(v_act.next_occurrence_date, v_act.start_date)::date END;

  IF v_act.type = 'free_repeat' THEN
    RETURN jsonb_build_object('ok', false, 'error', 'questo tipo non si può fallire');
  END IF;

  v_from_status := v_act.status;
  v_points      := COALESCE(v_act.failure_points, 0);
  v_time_of_day := COALESCE(v_act.start_date, now())
                   - date_trunc('day', COALESCE(v_act.start_date, now()));

  INSERT INTO ob_action_history (user_id, action_id, objective_id, action_title,
                                 from_status, to_status, action, points, occurrence_date)
  VALUES (v_act.user_id, p_action_id, v_act.objective_id, v_act.title,
          v_from_status, 'failed', 'failed', v_points, v_occ_date);

  IF v_act.type IN ('single', 'workflow') THEN
    UPDATE ob_actions
       SET status = 'terminated',
           last_completed_date = COALESCE(v_act.next_occurrence_date, v_act.start_date, now())
     WHERE id = p_action_id;

    INSERT INTO ob_action_history (user_id, action_id, objective_id, action_title,
                                   from_status, to_status, action, points, occurrence_date)
    VALUES (v_act.user_id, p_action_id, v_act.objective_id, v_act.title,
            'failed', 'terminated', 'terminated', 0, v_occ_date);

  ELSIF v_act.type = 'simple_recurring' THEN
    v_next_ts := COALESCE(v_act.next_occurrence_date, v_act.start_date, now())
                 + (COALESCE(v_act.repeat_after_days, 7) || ' days')::interval;
    UPDATE ob_actions SET status = 'failed', next_occurrence_date = v_next_ts
     WHERE id = p_action_id;

  ELSIF v_act.type = 'recurring' THEN
    v_next_date := ob_action_next_recurring_date(
      v_act, COALESCE(v_act.next_occurrence_date, v_act.start_date)::date);

    IF v_next_date IS NOT NULL THEN
      v_next_ts := v_next_date::timestamptz + v_time_of_day;
    END IF;

    UPDATE ob_actions
       SET status = CASE WHEN v_next_date IS NULL THEN 'terminated' ELSE 'failed' END,
           next_occurrence_date = v_next_ts
     WHERE id = p_action_id;

    IF v_next_date IS NULL THEN
      INSERT INTO ob_action_history (user_id, action_id, objective_id, action_title,
                                     from_status, to_status, action, points, occurrence_date)
      VALUES (v_act.user_id, p_action_id, v_act.objective_id, v_act.title,
              'failed', 'terminated', 'terminated', 0, v_occ_date);
    END IF;

  ELSE -- multiple
    SELECT array_agg(d ORDER BY d) INTO v_dates FROM unnest(v_act.multiple_dates) AS d;
    v_cur_str := COALESCE(v_act.next_occurrence_date::date::text, '');

    FOR j IN 1..COALESCE(array_length(v_dates, 1), 0) LOOP
      IF v_dates[j] = v_cur_str THEN
        v_cur_idx := j;
        EXIT;
      END IF;
    END LOOP;

    IF v_cur_idx IS NOT NULL AND v_cur_idx < array_length(v_dates, 1) THEN
      v_next_ts := v_dates[v_cur_idx + 1]::date::timestamptz + v_time_of_day;
    END IF;

    UPDATE ob_actions
       SET status = CASE WHEN v_next_ts IS NULL THEN 'terminated' ELSE 'failed' END,
           next_occurrence_date = v_next_ts
     WHERE id = p_action_id;

    IF v_next_ts IS NULL THEN
      INSERT INTO ob_action_history (user_id, action_id, objective_id, action_title,
                                     from_status, to_status, action, points, occurrence_date)
      VALUES (v_act.user_id, p_action_id, v_act.objective_id, v_act.title,
              'failed', 'terminated', 'terminated', 0, v_occ_date);
    END IF;
  END IF;

  RETURN jsonb_build_object('ok', true, 'action', 'failed', 'points', v_points,
                            'type', v_act.type, 'next', v_next_ts);
END;
$$;
