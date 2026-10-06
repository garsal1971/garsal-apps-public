-- Obiettivi — le AZIONI, un sistema proprio invece di un collegamento a Tasks
-- ===========================================================================
-- Prima il dettaglio di un obiettivo aveva «Azioni collegate»: un puntatore a
-- un task di `ts_tasks` o a un'abitudine di `hb_habits`, che restavano a vivere
-- nelle loro app. Da qui non si poteva né crearli, né completarli, né sapere
-- come fossero andati — il collegamento diceva soltanto che esistevano.
--
-- Le azioni sono ora **di Obiettivi**, su tabelle proprie: `ob_actions` e
-- `ob_action_history`, gemelle di `ts_tasks` e `ts_history`, con gli stessi sei
-- tipi e lo stesso ciclo di vita, clonato nelle RPC `ob_action_*`.
--
-- ⚠️ Perché tabelle separate e non un campo `objective_id` su `ts_tasks`: i
-- task sono letti da tasks.html, dal planner, dall'APK WebView, da AppSphere
-- nativa e dalle notifiche Smart Block. Un campo da filtrare regge finché
-- **ogni** query si ricorda di filtrarlo, ed è la stessa ragione per cui le
-- spese di Ada stanno nelle `ada_*` e non nelle `ca_*`.
--
-- ⚠️ Cancella dati: `ob_task_links` sparisce con i collegamenti già salvati. I
-- task e le abitudini che citava restano dove sono, intatti: si perde il
-- collegamento, non le cose collegate.
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- 1. ob_actions — la gemella di ts_tasks
--    Le colonne sono quelle di là, nome per nome, perché la logica clonata
--    nelle RPC possa restare leggibile accanto all'originale. Restano fuori
--    solo le due che qui non hanno un lettore: `riservato` (Obiettivi non ha
--    la modalità nascosta) e `show_in_panoramica` (non c'è una panoramica da
--    cui togliere qualcosa).
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ob_actions (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id       uuid DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
  objective_id  uuid NOT NULL REFERENCES ob_objectives(id) ON DELETE CASCADE,
  title         text NOT NULL,
  description   text,
  type          text NOT NULL DEFAULT 'single'
                CHECK (type IN ('single', 'recurring', 'simple_recurring',
                                'multiple', 'free_repeat', 'workflow')),
  status        text NOT NULL DEFAULT 'started'
                CHECK (status IN ('started', 'completed', 'skipped', 'failed', 'terminated')),

  -- Categorie condivise (cm_categories) e priorità condivise (cm_priorities):
  -- sono le stesse dei task, di proposito — una terza tassonomia da tenere
  -- allineata a mano sarebbe il difetto, non la separazione.
  -- ⚠️ Niente FOREIGN KEY: quelle due tabelle non stanno in nessuna migration
  -- (nascono a mano in produzione), e una FK farebbe fallire il db push su un
  -- database nuovo.
  categories    uuid[] NOT NULL DEFAULT '{}',
  priority_id   uuid,

  start_date            timestamptz NOT NULL DEFAULT now(),
  next_occurrence_date  timestamptz,
  deadline              date,
  last_completed_date   timestamptz,

  success_points  integer NOT NULL DEFAULT 10,
  failure_points  integer NOT NULL DEFAULT 0,
  skip_points     integer NOT NULL DEFAULT 0,
  late_points     integer NOT NULL DEFAULT 0,

  -- type = 'recurring'
  recurring_frequency     text CHECK (recurring_frequency IN ('daily','weekly','monthly','yearly')),
  recurring_interval      integer DEFAULT 1,
  -- ⚠️ i giorni della settimana sono numerati come extract(dow) di Postgres
  -- (0 = domenica): è quello che ob_action_next_recurring_date confronta.
  recurring_days_of_week  integer[],
  recurring_day_of_month  integer[],
  recurring_dates         text[],          -- 'DD-MM'

  -- type = 'simple_recurring'
  repeat_after_days       integer,

  -- type = 'multiple'
  multiple_dates          text[],          -- 'YYYY-MM-DD'

  -- type = 'workflow'
  workflow_steps   jsonb,
  workflow_points  jsonb NOT NULL DEFAULT
                   '{"step_success":5,"step_failure":-3,"task_success":20,"task_failure":-10}'::jsonb,

  sort_order  integer NOT NULL DEFAULT 0,
  created_at  timestamptz NOT NULL DEFAULT now(),

  -- Un workflow con meno di due step è una singola scritta in modo complicato:
  -- è il controllo che saveAction() fa già a schermo, qui perché regga comunque.
  CONSTRAINT ob_actions_workflow_ha_step CHECK (
    type <> 'workflow'
    OR (workflow_steps IS NOT NULL AND jsonb_array_length(workflow_steps) >= 2)
  )
);
CREATE INDEX IF NOT EXISTS idx_ob_actions_objective ON ob_actions(objective_id);
CREATE INDEX IF NOT EXISTS idx_ob_actions_user      ON ob_actions(user_id);
CREATE INDEX IF NOT EXISTS idx_ob_actions_next      ON ob_actions(next_occurrence_date);
ALTER TABLE ob_actions ENABLE ROW LEVEL SECURITY;
CREATE POLICY "ob_actions_own" ON ob_actions FOR ALL USING (user_id = auth.uid());

-- ---------------------------------------------------------------------------
-- 2. ob_action_history — la gemella di ts_history
--
-- ⚠️ `action_id` è ON DELETE SET NULL e non CASCADE, e la riga porta con sé
-- `action_title`: cancellare un'azione non deve riscrivere all'indietro i punti
-- già presi, e una riga che resta senza dire di che cosa parlava non si legge
-- più. È la stessa scelta di `al_log`, che porta i valori nutrizionali sulla
-- riga invece del solo `food_id`.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ob_action_history (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id       uuid DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
  action_id     uuid REFERENCES ob_actions(id) ON DELETE SET NULL,
  objective_id  uuid REFERENCES ob_objectives(id) ON DELETE CASCADE,
  action_title  text NOT NULL DEFAULT '',
  from_status   text,
  to_status     text,
  action        text NOT NULL,
  points        integer NOT NULL DEFAULT 0,
  timestamp     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_ob_action_history_action    ON ob_action_history(action_id);
CREATE INDEX IF NOT EXISTS idx_ob_action_history_objective ON ob_action_history(objective_id, timestamp DESC);
CREATE INDEX IF NOT EXISTS idx_ob_action_history_user      ON ob_action_history(user_id);
ALTER TABLE ob_action_history ENABLE ROW LEVEL SECURITY;
CREATE POLICY "ob_action_history_own" ON ob_action_history FOR ALL USING (user_id = auth.uid());

-- ---------------------------------------------------------------------------
-- 3. il collegamento a Tasks non serve più
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS ob_task_links;

-- ============================================================================
-- RPC — il ciclo di vita, clonato da task_complete / task_skip / task_fail
--
-- ⚠️ Vale qui la regola del CLAUDE.md sui task: **il calcolo della prossima
-- occorrenza vive nel database, non nel JavaScript**. Il client chiama la RPC
-- e ricarica; due implementazioni della stessa ricorrenza sono due date diverse
-- il giorno che una delle due cambia.
--
-- Due differenze volute rispetto alle `task_*`, da non "correggere" indietro:
--
--   1. la riga si cerca con `AND user_id = auth.uid()`. Le task_* sono
--      SECURITY DEFINER e la RLS lì dentro non vale: senza quel filtro basta
--      l'id di una riga altrui per completarla. Le task_* non ce l'hanno, ma
--      non è un motivo per rifare lo stesso buco;
--   2. non si tocca `cm_notification_rules`: le azioni non hanno (ancora)
--      promemoria Smart Block, e cancellare regole per `app = 'tasks'` da qui
--      vorrebbe dire spegnere le notifiche di un task che non c'entra niente.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- ob_action_next_recurring_date — clone di task_next_recurring_date.
-- unnest(col)::integer regge sia integer[] sia text[], come là.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION ob_action_next_recurring_date(
  p_action ob_actions,
  p_base   date
)
RETURNS date
LANGUAGE plpgsql
SET search_path = public
AS $$
DECLARE
  v_freq     text    := p_action.recurring_frequency;
  v_interval integer := COALESCE(p_action.recurring_interval, 1);
  v_dow_arr  integer[];
  v_dom_arr  integer[];
  v_dates    text[];
  v_cur_dow  integer;
  v_sun_base date;
  v_test     date;
  v_day      integer;
  v_yr       integer;
  v_parts    text[];
  v_d        integer;
  v_m        integer;
  v_str      text;
  i          integer;
BEGIN
  -- ── daily ──────────────────────────────────────────────────────────────
  IF v_freq = 'daily' THEN
    RETURN p_base + (v_interval || ' days')::interval;

  -- ── weekly ─────────────────────────────────────────────────────────────
  ELSIF v_freq = 'weekly' THEN
    IF p_action.recurring_days_of_week IS NOT NULL
       AND array_length(p_action.recurring_days_of_week, 1) > 0
    THEN
      SELECT ARRAY(SELECT unnest(p_action.recurring_days_of_week)::integer ORDER BY 1)
        INTO v_dow_arr;
    ELSE
      v_dow_arr := ARRAY[extract(dow FROM p_action.start_date::date)::integer];
    END IF;

    v_cur_dow := extract(dow FROM p_base)::integer;

    -- prima si guarda il resto della settimana corrente, da domani in poi
    FOR i IN 1..(7 - v_cur_dow) LOOP
      v_test := p_base + i;
      IF extract(dow FROM v_test)::integer = ANY(v_dow_arr) THEN
        RETURN v_test;
      END IF;
    END LOOP;

    DECLARE
      v_days_to_sun integer := (7 - v_cur_dow) % 7;
    BEGIN
      IF v_days_to_sun = 0 THEN v_days_to_sun := 7; END IF;
      v_sun_base := p_base + v_days_to_sun + (v_interval - 1) * 7;
    END;

    FOR i IN 0..6 LOOP
      IF extract(dow FROM (v_sun_base + i))::integer = ANY(v_dow_arr) THEN
        RETURN v_sun_base + i;
      END IF;
    END LOOP;

    RETURN NULL;

  -- ── monthly ────────────────────────────────────────────────────────────
  ELSIF v_freq = 'monthly' THEN
    IF p_action.recurring_day_of_month IS NOT NULL
       AND array_length(p_action.recurring_day_of_month, 1) > 0
    THEN
      SELECT ARRAY(SELECT unnest(p_action.recurring_day_of_month)::integer ORDER BY 1)
        INTO v_dom_arr;
    ELSE
      v_dom_arr := ARRAY[extract(day FROM p_action.start_date::date)::integer];
    END IF;

    FOREACH v_day IN ARRAY v_dom_arr LOOP
      IF v_day > extract(day FROM p_base)::integer THEN
        BEGIN
          RETURN make_date(extract(year FROM p_base)::integer,
                           extract(month FROM p_base)::integer, v_day);
        EXCEPTION WHEN OTHERS THEN NULL;
        END;
      END IF;
    END LOOP;

    v_test := (date_trunc('month', p_base) + (v_interval || ' months')::interval)::date;
    FOREACH v_day IN ARRAY v_dom_arr LOOP
      BEGIN
        RETURN make_date(extract(year FROM v_test)::integer,
                         extract(month FROM v_test)::integer, v_day);
      EXCEPTION WHEN OTHERS THEN NULL;
      END;
    END LOOP;

    RETURN NULL;

  -- ── yearly ─────────────────────────────────────────────────────────────
  ELSIF v_freq = 'yearly' THEN
    v_yr := extract(year FROM p_base)::integer;

    IF p_action.recurring_dates IS NOT NULL
       AND array_length(p_action.recurring_dates, 1) > 0
    THEN
      v_dates := p_action.recurring_dates;        -- 'DD-MM'
    ELSE
      RETURN NULL;
    END IF;

    FOREACH v_str IN ARRAY v_dates LOOP
      v_parts := string_to_array(v_str, '-');
      v_d := v_parts[1]::integer;
      v_m := v_parts[2]::integer;
      BEGIN
        v_test := make_date(v_yr, v_m, v_d);
        IF v_test > p_base THEN
          RETURN v_test;
        END IF;
      EXCEPTION WHEN OTHERS THEN NULL;
      END;
    END LOOP;

    v_parts := string_to_array(v_dates[1], '-');
    v_d := v_parts[1]::integer;
    v_m := v_parts[2]::integer;
    BEGIN
      RETURN make_date(v_yr + v_interval, v_m, v_d);
    EXCEPTION WHEN OTHERS THEN
      RETURN NULL;
    END;
  END IF;

  RETURN NULL;
END;
$$;

-- ---------------------------------------------------------------------------
-- ob_action_complete — clone di task_complete.
--
-- Comportamento per tipo, identico a quello dei task:
--   single           status → terminated, due righe di storico, fine
--   simple_recurring next = corrente + repeat_after_days, status → completed
--   recurring        next da ob_action_next_recurring_date; NULL → terminated
--   multiple         prossima data dell'elenco; se era l'ultima → terminated
--   workflow         guarda gli step: tutti fatti → terminated, altrimenti
--                    risponde senza cambiare lo status
--   free_repeat      status → completed, nessuna prossima occorrenza
-- ---------------------------------------------------------------------------
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
BEGIN
  SELECT * INTO v_act FROM ob_actions WHERE id = p_action_id AND user_id = auth.uid();
  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'error', 'azione non trovata');
  END IF;

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
                                   from_status, to_status, action, points)
    VALUES (v_act.user_id, p_action_id, v_act.objective_id, v_act.title,
            v_from_status, 'terminated', 'completed', v_points);

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
                                 from_status, to_status, action, points)
  VALUES (v_act.user_id, p_action_id, v_act.objective_id, v_act.title,
          v_from_status, 'completed', v_action, v_points);

  IF v_act.type = 'single' THEN
    UPDATE ob_actions
       SET status = 'terminated', last_completed_date = v_completed_date
     WHERE id = p_action_id;

    INSERT INTO ob_action_history (user_id, action_id, objective_id, action_title,
                                   from_status, to_status, action, points)
    VALUES (v_act.user_id, p_action_id, v_act.objective_id, v_act.title,
            'completed', 'terminated', 'terminated', 0);

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
                                     from_status, to_status, action, points)
      VALUES (v_act.user_id, p_action_id, v_act.objective_id, v_act.title,
              'completed', 'terminated', 'terminated', 0);
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
                                     from_status, to_status, action, points)
      VALUES (v_act.user_id, p_action_id, v_act.objective_id, v_act.title,
              'completed', 'terminated', 'terminated', 0);
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
-- ob_action_skip — clone di task_skip.
-- `p_days` vale solo per il tipo 'single': per gli altri la prossima
-- occorrenza è quella che il tipo stesso prevede, e spostarla di N giorni
-- vorrebbe dire sfasare la ricorrenza per sempre.
-- `free_repeat` non ha un salto: non ha una prossima volta da saltare.
-- ---------------------------------------------------------------------------
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
BEGIN
  SELECT * INTO v_act FROM ob_actions WHERE id = p_action_id AND user_id = auth.uid();
  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'error', 'azione non trovata');
  END IF;

  IF v_act.type = 'free_repeat' OR v_act.type = 'workflow' THEN
    RETURN jsonb_build_object('ok', false, 'error', 'questo tipo non si può saltare');
  END IF;

  v_from_status := v_act.status;
  v_points      := COALESCE(v_act.skip_points, 0);
  v_time_of_day := COALESCE(v_act.start_date, now())
                   - date_trunc('day', COALESCE(v_act.start_date, now()));

  INSERT INTO ob_action_history (user_id, action_id, objective_id, action_title,
                                 from_status, to_status, action, points)
  VALUES (v_act.user_id, p_action_id, v_act.objective_id, v_act.title,
          v_from_status, 'skipped', 'skipped', v_points);

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
                                     from_status, to_status, action, points)
      VALUES (v_act.user_id, p_action_id, v_act.objective_id, v_act.title,
              'skipped', 'terminated', 'terminated', 0);
    END IF;
  END IF;

  RETURN jsonb_build_object('ok', true, 'action', 'skipped', 'points', v_points,
                            'type', v_act.type, 'next', v_next_ts);
END;
$$;

-- ---------------------------------------------------------------------------
-- ob_action_fail — clone di task_fail.
-- Una singola fallita è finita (terminated): il fallimento non si ritenta,
-- si riapre semmai un'azione nuova. Le ricorrenti vanno alla volta dopo.
-- ---------------------------------------------------------------------------
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
BEGIN
  SELECT * INTO v_act FROM ob_actions WHERE id = p_action_id AND user_id = auth.uid();
  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'error', 'azione non trovata');
  END IF;

  IF v_act.type = 'free_repeat' THEN
    RETURN jsonb_build_object('ok', false, 'error', 'questo tipo non si può fallire');
  END IF;

  v_from_status := v_act.status;
  v_points      := COALESCE(v_act.failure_points, 0);
  v_time_of_day := COALESCE(v_act.start_date, now())
                   - date_trunc('day', COALESCE(v_act.start_date, now()));

  INSERT INTO ob_action_history (user_id, action_id, objective_id, action_title,
                                 from_status, to_status, action, points)
  VALUES (v_act.user_id, p_action_id, v_act.objective_id, v_act.title,
          v_from_status, 'failed', 'failed', v_points);

  IF v_act.type IN ('single', 'workflow') THEN
    UPDATE ob_actions
       SET status = 'terminated',
           last_completed_date = COALESCE(v_act.next_occurrence_date, v_act.start_date, now())
     WHERE id = p_action_id;

    INSERT INTO ob_action_history (user_id, action_id, objective_id, action_title,
                                   from_status, to_status, action, points)
    VALUES (v_act.user_id, p_action_id, v_act.objective_id, v_act.title,
            'failed', 'terminated', 'terminated', 0);

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
                                     from_status, to_status, action, points)
      VALUES (v_act.user_id, p_action_id, v_act.objective_id, v_act.title,
              'failed', 'terminated', 'terminated', 0);
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
                                     from_status, to_status, action, points)
      VALUES (v_act.user_id, p_action_id, v_act.objective_id, v_act.title,
              'failed', 'terminated', 'terminated', 0);
    END IF;
  END IF;

  RETURN jsonb_build_object('ok', true, 'action', 'failed', 'points', v_points,
                            'type', v_act.type, 'next', v_next_ts);
END;
$$;

-- ---------------------------------------------------------------------------
-- ob_objective_progress — la barra «Esecuzione» conta anche le azioni.
--
-- Le DUE barre restano due e non si fondono mai in una media: cambia solo cosa
-- entra nella seconda, che è «quanto del piano ho eseguito» — e le azioni sono
-- il piano, più dei sotto-obiettivi e delle milestone messi insieme.
--
-- ⚠️ Entrano le sole azioni che possono **finire** (single, multiple,
-- workflow). Una ricorrente e una a libera ripetizione non finiscono mai: al
-- denominatore resterebbero per sempre, tenendo l'esecuzione sotto il 100 % a
-- piano concluso — e sarebbero un rimprovero per un'abitudine che sta
-- funzionando.
--
-- ⚠️ Al numeratore ci va l'azione **riuscita**, non quella chiusa: `terminated`
-- lo diventa anche fallendo (ob_action_fail), e un piano fallito che riempie la
-- barra dell'esecuzione direbbe esattamente il contrario di quel che è successo.
-- La riuscita si legge dallo storico, che è il posto dove è scritta.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION ob_objective_progress(p_objective_id uuid)
RETURNS jsonb
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_obj         ob_objectives%ROWTYPE;
  v_metric      ob_metrics%ROWTYPE;
  v_da          numeric;
  v_a           numeric;
  v_current     numeric;
  v_result      numeric := NULL;
  v_children    integer;
  v_children_ok integer;
  v_ms_total    integer;
  v_ms_hit      integer;
  v_act_total   integer;
  v_act_done    integer;
  v_execution   numeric := NULL;
  v_expected    numeric;
  v_exp_prog    numeric;
  v_status      text := 'unknown';
BEGIN
  SELECT * INTO v_obj FROM ob_objectives WHERE id = p_objective_id AND user_id = auth.uid();
  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'error', 'obiettivo non trovato');
  END IF;

  -- --- barra 1: risultato (metrica primaria) ---
  -- unica per costruzione (uq_ob_metrics_one_primary)
  SELECT * INTO v_metric
    FROM ob_metrics
   WHERE objective_id = p_objective_id AND role = 'primary';

  IF FOUND THEN
    SELECT da, a INTO v_da, v_a FROM ob_metric_scale(v_metric);
    v_current := ob_metric_current(v_metric);
    IF v_a IS DISTINCT FROM v_da THEN
      v_result := GREATEST(0, LEAST(1, (v_current - v_da) / (v_a - v_da)));
    END IF;
  END IF;

  -- --- barra 2: esecuzione del piano (figli + milestone + azioni) ---
  SELECT COUNT(*), COUNT(*) FILTER (WHERE status = 'achieved')
    INTO v_children, v_children_ok
    FROM ob_objectives WHERE parent_id = p_objective_id;

  SELECT COUNT(*), COUNT(*) FILTER (WHERE status = 'hit')
    INTO v_ms_total, v_ms_hit
    FROM ob_milestones WHERE objective_id = p_objective_id;

  SELECT COUNT(*),
         COUNT(*) FILTER (
           WHERE EXISTS (SELECT 1 FROM ob_action_history h
                          WHERE h.action_id = a.id
                            AND h.action IN ('completed', 'completed_late')))
    INTO v_act_total, v_act_done
    FROM ob_actions a
   WHERE a.objective_id = p_objective_id
     AND a.type IN ('single', 'multiple', 'workflow');

  IF (v_children + v_ms_total + v_act_total) > 0 THEN
    v_execution := (v_children_ok + v_ms_hit + v_act_done)::numeric
                   / (v_children + v_ms_total + v_act_total);
  END IF;

  -- --- semaforo: dove dovrei essere adesso secondo le milestone ---
  IF v_metric.id IS NOT NULL THEN
    SELECT expected_value INTO v_expected
      FROM ob_milestones
     WHERE objective_id = p_objective_id
       AND expected_value IS NOT NULL
       AND due_date <= CURRENT_DATE
     ORDER BY due_date DESC LIMIT 1;

    IF v_expected IS NULL THEN
      v_status := 'on_track';   -- nessuna milestone ancora scaduta: niente da rimproverare
    ELSE
      v_exp_prog := (v_expected - v_da) / (v_a - v_da);
      v_status := CASE
        WHEN v_result >= v_exp_prog        THEN 'on_track'
        WHEN v_result >= v_exp_prog * 0.75 THEN 'at_risk'
        ELSE 'off_track'
      END;
    END IF;
  END IF;

  RETURN jsonb_build_object(
    'ok', true,
    'objective_id', p_objective_id,
    'result', ROUND(COALESCE(v_result, 0) * 100),
    'has_result', v_result IS NOT NULL,
    'execution', ROUND(COALESCE(v_execution, 0) * 100),
    'has_execution', v_execution IS NOT NULL,
    'current_value', v_current,
    'expected_value', v_expected,
    'status', v_status,
    'children_total', v_children,
    'children_done', v_children_ok,
    'milestones_total', v_ms_total,
    'milestones_hit', v_ms_hit,
    'actions_total', v_act_total,
    'actions_done', v_act_done
  );
END;
$$;
