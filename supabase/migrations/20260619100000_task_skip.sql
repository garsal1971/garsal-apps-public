CREATE OR REPLACE FUNCTION task_skip(
  p_task_id uuid,
  p_days    integer DEFAULT 1   -- usato solo per tipo 'single'
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_task        ts_tasks%ROWTYPE;
  v_from_status text;
  v_points      integer;
  v_next_date   date;
  v_next_ts     timestamptz;
  v_time_of_day interval;

  -- multiple
  v_dates    text[];
  v_cur_str  text;
  v_cur_idx  integer := NULL;
  j          integer;
BEGIN
  SELECT * INTO v_task FROM ts_tasks WHERE id = p_task_id;

  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'error', 'task non trovato');
  END IF;

  v_from_status := v_task.status;
  v_points      := COALESCE(v_task.skip_points, 0);

  -- Orario originale preservato (come in task_complete)
  v_time_of_day := COALESCE(v_task.start_date, now())
                   - date_trunc('day', COALESCE(v_task.start_date, now()));

  -- Storia: skip
  INSERT INTO ts_history (task_id, from_status, to_status, action, points, timestamp)
  VALUES (p_task_id, v_from_status, 'skipped', 'skipped', v_points, now());

  -- ── single ──────────────────────────────────────────────────────────────
  IF v_task.type = 'single' THEN
    v_next_ts := COALESCE(v_task.next_occurrence_date, v_task.start_date, now())
                 + (p_days || ' days')::interval;

    UPDATE ts_tasks
       SET status               = 'skipped',
           next_occurrence_date = v_next_ts
     WHERE id = p_task_id;

    UPDATE cm_notification_rules
       SET reminder_presets = reminder_presets || jsonb_build_object('due_at', v_next_ts)
     WHERE entity_id = p_task_id AND app = 'tasks';

  -- ── simple_recurring ─────────────────────────────────────────────────────
  ELSIF v_task.type = 'simple_recurring' THEN
    v_next_ts := COALESCE(v_task.next_occurrence_date, v_task.start_date, now())
                 + (COALESCE(v_task.repeat_after_days, 7) || ' days')::interval;

    UPDATE ts_tasks
       SET status               = 'skipped',
           next_occurrence_date = v_next_ts
     WHERE id = p_task_id;

    UPDATE cm_notification_rules
       SET reminder_presets = reminder_presets || jsonb_build_object('due_at', v_next_ts)
     WHERE entity_id = p_task_id AND app = 'tasks';

  -- ── recurring ────────────────────────────────────────────────────────────
  ELSIF v_task.type = 'recurring' THEN
    v_next_date := task_next_recurring_date(
      v_task,
      COALESCE(v_task.next_occurrence_date::text, v_task.start_date::text)::date
    );

    IF v_next_date IS NULL THEN
      RETURN jsonb_build_object('ok', false, 'error', 'impossibile calcolare prossima occorrenza');
    END IF;

    v_next_ts := v_next_date::timestamptz + v_time_of_day;

    UPDATE ts_tasks
       SET status               = 'skipped',
           next_occurrence_date = v_next_ts
     WHERE id = p_task_id;

    UPDATE cm_notification_rules
       SET reminder_presets = reminder_presets || jsonb_build_object('due_at', v_next_ts)
     WHERE entity_id = p_task_id AND app = 'tasks';

  -- ── multiple ─────────────────────────────────────────────────────────────
  ELSIF v_task.type = 'multiple' THEN
    SELECT array_agg(d ORDER BY d)
      INTO v_dates
      FROM unnest(v_task.multiple_dates) AS d;

    -- FIX: ::date::text → 'YYYY-MM-DD'
    v_cur_str := COALESCE(v_task.next_occurrence_date::date::text, '');

    FOR j IN 1..array_length(v_dates, 1) LOOP
      IF v_dates[j] = v_cur_str THEN
        v_cur_idx := j;
        EXIT;
      END IF;
    END LOOP;

    IF v_cur_idx IS NOT NULL AND v_cur_idx < array_length(v_dates, 1) THEN
      v_next_ts := v_dates[v_cur_idx + 1]::date::timestamptz + v_time_of_day;
    END IF;

    UPDATE ts_tasks
       SET status               = CASE WHEN v_next_ts IS NULL THEN 'terminated' ELSE 'skipped' END,
           next_occurrence_date = v_next_ts
     WHERE id = p_task_id;

    IF v_next_ts IS NULL THEN
      INSERT INTO ts_history (task_id, from_status, to_status, action, points, timestamp)
      VALUES (p_task_id, 'skipped', 'terminated', 'terminated', 0, now());

      DELETE FROM cm_notification_rules
       WHERE entity_id = p_task_id AND app = 'tasks';
    ELSE
      UPDATE cm_notification_rules
         SET reminder_presets = reminder_presets || jsonb_build_object('due_at', v_next_ts)
       WHERE entity_id = p_task_id AND app = 'tasks';
    END IF;

  ELSE
    -- free_repeat non ha bottone skip
    RETURN jsonb_build_object('ok', false, 'error', 'tipo non supporta skip');
  END IF;

  RETURN jsonb_build_object(
    'ok',     true,
    'action', 'skipped',
    'points', v_points,
    'type',   v_task.type,
    'next',   v_next_ts
  );
END;
$$;
