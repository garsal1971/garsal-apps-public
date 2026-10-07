-- ============================================================================
-- Obiettivi — i promemoria delle azioni (Telegram, telefono, Smart Block)
-- ============================================================================
-- Le azioni di Obiettivi prendono i promemoria come le azioni delle Piante
-- (20260926100000_pv_piante.sql), con app = 'objectives' in
-- cm_notification_rules / cm_notification_queue. Nessuna colonna nuova.
--
-- ⚠️ Fino a qui le RPC ob_action_* NON toccavano cm_notification_rules, di
-- proposito: le azioni non avevano promemoria. Adesso li hanno, e le RPC fanno
-- come le task_* e le pv_action_*: spostano `due_at` delle regole alla prossima
-- occorrenza, e le tolgono quando l'azione finisce. Il client le scrive solo
-- salvando il form (sincronizzaRegole() in obiettivi.html).
--
-- ⚠️ Il filtro è sempre `app = 'objectives'`: cancellare regole per
-- `app = 'tasks'` da qui spegnerebbe le notifiche di un task che non c'entra.
--
-- La struttura è quella delle Piante:
--   ob_action_complete_core / ob_action_skip_core (p_user) — solo service_role,
--       per notification-action, dove auth.uid() è NULL
--   ob_action_complete / ob_action_skip — le porte dell'utente, col JWT
--   ob_smart_block_complete — l'APK Smart Blocker, con la anon key, solo se
--       l'azione ha davvero un blocco smart_block in coda
--   trg_ob_actions_togli_promemoria — un'azione cancellata si porta via le regole
--
-- Il comportamento per tipo NON cambia: i due nuclei sono i corpi di
-- 20260831100000_ob_action_history_occurrence_date.sql con `p_user` al posto
-- di `auth.uid()` e lo spostamento dei promemoria in coda.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- I promemoria seguono l'azione: nuova scadenza su tutti i canali, oppure via.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION ob__sposta_promemoria(p_action_id uuid, p_user uuid, p_next timestamptz)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
  IF p_next IS NULL THEN
    DELETE FROM cm_notification_queue
     WHERE app = 'objectives' AND entity_id::text = p_action_id::text
       AND user_id = p_user AND status = 'pending';
    DELETE FROM cm_notification_rules
     WHERE app = 'objectives' AND entity_id::text = p_action_id::text AND user_id = p_user;
  ELSE
    UPDATE cm_notification_rules
       SET reminder_presets = reminder_presets || jsonb_build_object('due_at', p_next)
     WHERE app = 'objectives' AND entity_id::text = p_action_id::text AND user_id = p_user;
  END IF;
END;
$$;

-- ---------------------------------------------------------------------------
-- Il nucleo del completamento
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION ob_action_complete_core(
  p_action_id uuid,
  p_user      uuid,
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
  SELECT * INTO v_act FROM ob_actions WHERE id = p_action_id AND user_id = p_user;
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
    PERFORM ob__sposta_promemoria(p_action_id, v_act.user_id, NULL);

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

  -- I promemoria seguono l'azione: alla prossima volta, o via se è finita.
  -- Una libera ripetizione non ne ha (senza data non c'è niente a cui suonare).
  IF v_act.type <> 'free_repeat' THEN
    PERFORM ob__sposta_promemoria(p_action_id, v_act.user_id, v_next_ts);
  END IF;

  RETURN jsonb_build_object('ok', true, 'action', v_action, 'points', v_points,
                            'type', v_act.type, 'next', v_next_ts);
END;
$$;

-- ---------------------------------------------------------------------------
-- Il nucleo del salto
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION ob_action_skip_core(
  p_action_id uuid,
  p_user      uuid,
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
  SELECT * INTO v_act FROM ob_actions WHERE id = p_action_id AND user_id = p_user;
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

  PERFORM ob__sposta_promemoria(p_action_id, v_act.user_id, v_next_ts);

  RETURN jsonb_build_object('ok', true, 'action', 'skipped', 'points', v_points,
                            'type', v_act.type, 'next', v_next_ts);
END;
$$;

-- ── le porte dell'utente ────────────────────────────────────────────────────
-- Stessa firma di prima: web e nativo continuano a chiamare le stesse RPC.
CREATE OR REPLACE FUNCTION ob_action_complete(p_action_id uuid, p_today date DEFAULT CURRENT_DATE)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
  IF auth.uid() IS NULL THEN
    RETURN jsonb_build_object('ok', false, 'error', 'serve un login');
  END IF;
  RETURN ob_action_complete_core(p_action_id, auth.uid(), p_today);
END;
$$;

CREATE OR REPLACE FUNCTION ob_action_skip(p_action_id uuid, p_days integer DEFAULT 1)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
  IF auth.uid() IS NULL THEN
    RETURN jsonb_build_object('ok', false, 'error', 'serve un login');
  END IF;
  RETURN ob_action_skip_core(p_action_id, auth.uid(), p_days);
END;
$$;

-- Smart Blocker non ha un login: parla con la anon key. ⚠️ La guardia è che
-- quell'azione abbia DAVVERO un blocco in coda sul canale smart_block: senza,
-- la anon key basterebbe a completare qualunque azione di cui si conosca l'id.
CREATE OR REPLACE FUNCTION ob_smart_block_complete(p_action_id uuid, p_today date DEFAULT CURRENT_DATE)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_user uuid;
BEGIN
  SELECT q.user_id INTO v_user
    FROM cm_notification_queue q
   WHERE q.app = 'objectives' AND q.channel = 'smart_block'
     AND q.entity_id::text = p_action_id::text
     AND q.status IN ('pending', 'sent')
   LIMIT 1;
  IF v_user IS NULL THEN
    RETURN jsonb_build_object('ok', false, 'error', 'nessun blocco per questa azione');
  END IF;
  RETURN ob_action_complete_core(p_action_id, v_user, p_today);
END;
$$;

-- ⚠️ A PUBLIC e non ad anon/authenticated: l'EXECUTE nasce concesso a PUBLIC e
-- i due ruoli lo ereditano da lì (vedi le vg_* in CLAUDE.md).
REVOKE ALL ON FUNCTION ob_action_complete_core(uuid, uuid, date) FROM PUBLIC;
REVOKE ALL ON FUNCTION ob_action_skip_core(uuid, uuid, integer)  FROM PUBLIC;
REVOKE ALL ON FUNCTION ob__sposta_promemoria(uuid, uuid, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ob_action_complete_core(uuid, uuid, date) TO service_role;
GRANT EXECUTE ON FUNCTION ob_action_skip_core(uuid, uuid, integer)  TO service_role;
GRANT EXECUTE ON FUNCTION ob_action_complete(uuid, date)       TO authenticated;
GRANT EXECUTE ON FUNCTION ob_action_skip(uuid, integer)        TO authenticated;
GRANT EXECUTE ON FUNCTION ob_smart_block_complete(uuid, date)  TO anon, authenticated;

-- ── un'azione cancellata si porta via i suoi promemoria ─────────────────────
-- In un trigger e non nel client: la cancellano il form, la zona pericolosa e
-- la cascata di un obiettivo eliminato, e una regola orfana suonerebbe per
-- un'azione che non c'è più.
CREATE OR REPLACE FUNCTION ob_actions_togli_promemoria()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
  PERFORM ob__sposta_promemoria(OLD.id, OLD.user_id, NULL);
  RETURN OLD;
END;
$$;

DROP TRIGGER IF EXISTS trg_ob_actions_togli_promemoria ON ob_actions;
CREATE TRIGGER trg_ob_actions_togli_promemoria
  AFTER DELETE ON ob_actions
  FOR EACH ROW EXECUTE FUNCTION ob_actions_togli_promemoria();
