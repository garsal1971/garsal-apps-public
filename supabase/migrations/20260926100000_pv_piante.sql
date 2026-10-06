-- ============================================================================
-- Piante — il diario di cura, pianta per pianta (`pv_*`)
--
-- Una pianta ha:
--   • la sua SCHEDA (pv_plants) con le informazioni di cura e le sue foto;
--   • un DIARIO cronologico (pv_diary), ogni voce con commento e foto;
--   • le AZIONI (pv_actions / pv_action_history), gemelle di ts_tasks /
--     ts_history e di ob_actions, con promemoria su Telegram, telefono e
--     Smart Block;
--   • le domande fatte all'IA (pv_ai_answers), che restano rileggibili.
-- Accanto, la LISTA DEI DESIDERI (pv_wishlist): le piante che si vorrebbero.
--
-- ⚠️ Le azioni stanno su tabelle proprie e non su ts_tasks, per la stessa
-- ragione di ob_actions: i task sono letti da tasks.html, dal planner, dalle
-- due APK e dalle notifiche, e un campo `plant_id` da filtrare reggerebbe solo
-- finché ogni query si ricorda di filtrarlo.
--
-- ⚠️ Le foto stanno nel bucket PRIVATO `pv-images`, percorso
-- `<user_id>/<plant_id>/<uuid>.jpg`: la prima cartella è quella su cui la
-- policy confronta auth.uid(), com'è per mm-images e mod-immagini.
-- ============================================================================

-- ── 1. le piante ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS pv_plants (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id      uuid NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
  name         text NOT NULL,
  species      text,           -- nome botanico o comune della specie
  location     text,           -- dove sta: «balcone a sud», «soggiorno»
  acquired_on  date,           -- quando è arrivata
  light        text,           -- esigenze di luce
  watering     text,           -- come e quanto annaffiarla
  soil         text,           -- terriccio e vaso
  fertilizer   text,           -- concime
  notes        text,
  -- Archiviata e non cancellata: una pianta che non c'è più resta col suo
  -- diario, che è la parte che vale la pena rileggere.
  archived     boolean NOT NULL DEFAULT false,
  created_at   timestamptz NOT NULL DEFAULT now(),
  updated_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS pv_plants_user_idx ON pv_plants (user_id, archived, name);
ALTER TABLE pv_plants ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS pv_plants_owner ON pv_plants;
CREATE POLICY pv_plants_owner ON pv_plants
  FOR ALL USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());

-- ── 2. il diario ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS pv_diary (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     uuid NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
  plant_id    uuid NOT NULL REFERENCES pv_plants(id) ON DELETE CASCADE,
  entry_date  date NOT NULL DEFAULT CURRENT_DATE,
  comment     text,
  created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS pv_diary_plant_idx ON pv_diary (plant_id, entry_date DESC, created_at DESC);
ALTER TABLE pv_diary ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS pv_diary_owner ON pv_diary;
CREATE POLICY pv_diary_owner ON pv_diary
  FOR ALL USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());

-- ── 3. le foto ──────────────────────────────────────────────────────────────
-- `diary_id` NULL = foto della SCHEDA (la prima per position è la copertina);
-- valorizzato = foto di una voce del diario. Nessuna colonna «copertina» sulla
-- pianta: sarebbe una seconda verità su quale foto viene prima.
--
-- ⚠️ ON DELETE CASCADE vale per la riga, non per il file: lo storage il vincolo
-- non lo conosce, quindi le app cancellano PRIMA i file e POI la riga, come in
-- Memo e in Modifiche.
CREATE TABLE IF NOT EXISTS pv_images (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id       uuid NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
  plant_id      uuid NOT NULL REFERENCES pv_plants(id) ON DELETE CASCADE,
  diary_id      uuid REFERENCES pv_diary(id) ON DELETE CASCADE,
  storage_path  text NOT NULL,
  position      int  NOT NULL DEFAULT 0,
  created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS pv_images_plant_idx ON pv_images (plant_id, diary_id, position);
ALTER TABLE pv_images ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS pv_images_owner ON pv_images;
CREATE POLICY pv_images_owner ON pv_images
  FOR ALL USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());

-- ── 4. le azioni — gemella di ts_tasks / ob_actions ─────────────────────────
CREATE TABLE IF NOT EXISTS pv_actions (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id       uuid NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
  plant_id      uuid NOT NULL REFERENCES pv_plants(id) ON DELETE CASCADE,
  title         text NOT NULL,
  description   text,
  type          text NOT NULL DEFAULT 'single'
                CHECK (type IN ('single', 'recurring', 'simple_recurring',
                                'multiple', 'free_repeat', 'workflow')),
  status        text NOT NULL DEFAULT 'started'
                CHECK (status IN ('started', 'completed', 'skipped', 'failed', 'terminated')),

  start_date            timestamptz NOT NULL DEFAULT now(),
  next_occurrence_date  timestamptz,
  deadline              date,
  last_completed_date   timestamptz,

  success_points  integer NOT NULL DEFAULT 10,
  failure_points  integer NOT NULL DEFAULT 0,
  skip_points     integer NOT NULL DEFAULT -2,
  late_points     integer NOT NULL DEFAULT -2,

  recurring_frequency     text CHECK (recurring_frequency IN ('daily','weekly','monthly','yearly')),
  recurring_interval      integer DEFAULT 1,
  -- ⚠️ numerati come extract(dow) di Postgres (0 = domenica)
  recurring_days_of_week  integer[],
  recurring_day_of_month  integer[],
  recurring_dates         text[],          -- 'DD-MM'
  repeat_after_days       integer,
  multiple_dates          text[],          -- 'YYYY-MM-DD'
  workflow_steps   jsonb,
  workflow_points  jsonb NOT NULL DEFAULT
                   '{"step_success":5,"step_failure":-3,"task_success":20,"task_failure":-10}'::jsonb,

  created_at  timestamptz NOT NULL DEFAULT now(),

  CONSTRAINT pv_actions_workflow_ha_step CHECK (
    type <> 'workflow'
    OR (workflow_steps IS NOT NULL AND jsonb_array_length(workflow_steps) >= 2)
  )
);
CREATE INDEX IF NOT EXISTS pv_actions_plant_idx ON pv_actions (plant_id);
CREATE INDEX IF NOT EXISTS pv_actions_user_idx  ON pv_actions (user_id, next_occurrence_date);
ALTER TABLE pv_actions ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS pv_actions_owner ON pv_actions;
CREATE POLICY pv_actions_owner ON pv_actions
  FOR ALL USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());

-- ⚠️ action_id ON DELETE SET NULL e action_title sulla riga: cancellare
-- un'azione non riscrive all'indietro i punti già presi (come ob_action_history).
CREATE TABLE IF NOT EXISTS pv_action_history (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id          uuid NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
  action_id        uuid REFERENCES pv_actions(id) ON DELETE SET NULL,
  plant_id         uuid REFERENCES pv_plants(id) ON DELETE SET NULL,
  action_title     text NOT NULL DEFAULT '',
  from_status      text,
  to_status        text,
  action           text NOT NULL,
  points           integer NOT NULL DEFAULT 0,
  occurrence_date  date,
  timestamp        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS pv_action_history_action_idx ON pv_action_history (action_id);
CREATE INDEX IF NOT EXISTS pv_action_history_user_idx   ON pv_action_history (user_id, timestamp DESC);
ALTER TABLE pv_action_history ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS pv_action_history_owner ON pv_action_history;
CREATE POLICY pv_action_history_owner ON pv_action_history
  FOR ALL USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());

-- ── 5. la lista dei desideri ────────────────────────────────────────────────
-- Tre stati e non un booleano: una pianta scartata non è una pianta presa, e
-- resta a schermo nel suo filtro invece di sparire. `plant_id` collega il
-- desiderio alla pianta nata quando lo si è preso.
CREATE TABLE IF NOT EXISTS pv_wishlist (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     uuid NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
  name        text NOT NULL,
  species     text,
  notes       text,
  priority    int  NOT NULL DEFAULT 2 CHECK (priority BETWEEN 1 AND 3),   -- 1 alta · 3 bassa
  status      text NOT NULL DEFAULT 'desiderata'
              CHECK (status IN ('desiderata', 'presa', 'scartata')),
  plant_id    uuid REFERENCES pv_plants(id) ON DELETE SET NULL,
  created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS pv_wishlist_user_idx ON pv_wishlist (user_id, status, priority);
ALTER TABLE pv_wishlist ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS pv_wishlist_owner ON pv_wishlist;
CREATE POLICY pv_wishlist_owner ON pv_wishlist
  FOR ALL USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());

-- ── 6. le risposte dell'IA ──────────────────────────────────────────────────
-- Le scrive la Edge Function `pv-ai` (col JWT dell'utente, quindi sotto RLS).
-- `plant_id` NULL = domanda generale (per esempio sulla lista dei desideri).
CREATE TABLE IF NOT EXISTS pv_ai_answers (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     uuid NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
  plant_id    uuid REFERENCES pv_plants(id) ON DELETE CASCADE,
  question    text NOT NULL,
  answer      text NOT NULL,
  model       text,
  created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS pv_ai_answers_plant_idx ON pv_ai_answers (user_id, plant_id, created_at DESC);
ALTER TABLE pv_ai_answers ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS pv_ai_answers_owner ON pv_ai_answers;
CREATE POLICY pv_ai_answers_owner ON pv_ai_answers
  FOR ALL USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());

-- ── 7. il bucket delle foto ─────────────────────────────────────────────────
-- ⚠️ Se non passa la migration non muore: avvisa. Il bucket va allora creato
-- a mano (privato, stesso nome), e la pagina lo dice al primo caricamento.
DO $$
BEGIN
  INSERT INTO storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
  VALUES ('pv-images', 'pv-images', false, 8388608,
          ARRAY['image/jpeg', 'image/png', 'image/webp'])
  ON CONFLICT (id) DO NOTHING;
EXCEPTION WHEN OTHERS THEN
  RAISE WARNING 'bucket pv-images non creato dalla migration (%): crealo a mano, privato', SQLERRM;
END;
$$;

DO $$
BEGIN
  DROP POLICY IF EXISTS "pv-images scrive" ON storage.objects;
  CREATE POLICY "pv-images scrive" ON storage.objects FOR INSERT
    WITH CHECK (bucket_id = 'pv-images' AND auth.uid()::text = (storage.foldername(name))[1]);

  DROP POLICY IF EXISTS "pv-images legge" ON storage.objects;
  CREATE POLICY "pv-images legge" ON storage.objects FOR SELECT
    USING (bucket_id = 'pv-images' AND auth.uid()::text = (storage.foldername(name))[1]);

  DROP POLICY IF EXISTS "pv-images cancella" ON storage.objects;
  CREATE POLICY "pv-images cancella" ON storage.objects FOR DELETE
    USING (bucket_id = 'pv-images' AND auth.uid()::text = (storage.foldername(name))[1]);
EXCEPTION WHEN OTHERS THEN
  RAISE WARNING 'policy di storage per pv-images non create (%): falle a mano', SQLERRM;
END;
$$;

-- ============================================================================
-- RPC — il ciclo di vita delle azioni
--
-- ⚠️ Vale la regola del CLAUDE.md sui task: la prossima occorrenza si calcola
-- QUI e non nel client (web o Kotlin). Il client chiama e rilegge.
--
-- A differenza di ob_action_*, qui i promemoria ci sono, quindi — come le
-- task_* — le RPC aggiornano `due_at` delle regole in cm_notification_rules
-- (app = 'plants', tutti i canali) e le cancellano quando l'azione finisce.
--
-- Tre porte sullo stesso nucleo:
--   pv_action_complete / pv_action_skip  — l'utente, col suo JWT (auth.uid())
--   pv_action_complete_core              — solo service_role (notification-action)
--   pv_smart_block_complete              — l'APK Smart Blocker, con la anon key
-- ============================================================================

CREATE OR REPLACE FUNCTION pv_action_next_recurring_date(
  p_action pv_actions,
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
  IF v_freq = 'daily' THEN
    RETURN p_base + (v_interval || ' days')::interval;

  ELSIF v_freq = 'weekly' THEN
    IF p_action.recurring_days_of_week IS NOT NULL
       AND array_length(p_action.recurring_days_of_week, 1) > 0 THEN
      SELECT ARRAY(SELECT unnest(p_action.recurring_days_of_week)::integer ORDER BY 1) INTO v_dow_arr;
    ELSE
      v_dow_arr := ARRAY[extract(dow FROM p_action.start_date::date)::integer];
    END IF;

    v_cur_dow := extract(dow FROM p_base)::integer;
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

  ELSIF v_freq = 'monthly' THEN
    IF p_action.recurring_day_of_month IS NOT NULL
       AND array_length(p_action.recurring_day_of_month, 1) > 0 THEN
      SELECT ARRAY(SELECT unnest(p_action.recurring_day_of_month)::integer ORDER BY 1) INTO v_dom_arr;
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

  ELSIF v_freq = 'yearly' THEN
    v_yr := extract(year FROM p_base)::integer;
    IF p_action.recurring_dates IS NOT NULL
       AND array_length(p_action.recurring_dates, 1) > 0 THEN
      v_dates := p_action.recurring_dates;
    ELSE
      RETURN NULL;
    END IF;

    FOREACH v_str IN ARRAY v_dates LOOP
      v_parts := string_to_array(v_str, '-');
      v_d := v_parts[1]::integer;
      v_m := v_parts[2]::integer;
      BEGIN
        v_test := make_date(v_yr, v_m, v_d);
        IF v_test > p_base THEN RETURN v_test; END IF;
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
-- I promemoria seguono l'azione: nuova scadenza su tutti i canali, oppure via.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION pv__sposta_promemoria(p_action_id uuid, p_user uuid, p_next timestamptz)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
  IF p_next IS NULL THEN
    DELETE FROM cm_notification_queue
     WHERE app = 'plants' AND entity_id::text = p_action_id::text
       AND user_id = p_user AND status = 'pending';
    DELETE FROM cm_notification_rules
     WHERE app = 'plants' AND entity_id::text = p_action_id::text AND user_id = p_user;
  ELSE
    UPDATE cm_notification_rules
       SET reminder_presets = reminder_presets || jsonb_build_object('due_at', p_next)
     WHERE app = 'plants' AND entity_id::text = p_action_id::text AND user_id = p_user;
  END IF;
END;
$$;

-- ---------------------------------------------------------------------------
-- Il nucleo del completamento — comportamento per tipo identico a task_complete
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION pv_action_complete_core(
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
  v_act            pv_actions%ROWTYPE;
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
  v_dates          text[];
  v_cur_str        text;
  v_cur_idx        integer := NULL;
  j                integer;
  v_occ_date       date;
  v_finita         boolean := false;
BEGIN
  SELECT * INTO v_act FROM pv_actions WHERE id = p_action_id AND user_id = p_user;
  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'error', 'azione non trovata');
  END IF;

  -- Il giorno per cui l'azione era in calendario, letto PRIMA di spostarla.
  v_occ_date := CASE WHEN v_act.type = 'free_repeat' THEN NULL
                     ELSE COALESCE(v_act.next_occurrence_date, v_act.start_date)::date END;

  v_from_status    := v_act.status;
  v_points         := COALESCE(v_act.success_points, 0);
  v_completed_date := COALESCE(v_act.next_occurrence_date, v_act.start_date, now());
  v_time_of_day    := COALESCE(v_act.start_date, now())
                      - date_trunc('day', COALESCE(v_act.start_date, now()));

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
    INSERT INTO pv_action_history (user_id, action_id, plant_id, action_title,
                                   from_status, to_status, action, points, occurrence_date)
    VALUES (v_act.user_id, p_action_id, v_act.plant_id, v_act.title,
            v_from_status, 'terminated', 'completed', v_points, v_occ_date);
    UPDATE pv_actions SET status = 'terminated', last_completed_date = now() WHERE id = p_action_id;
    PERFORM pv__sposta_promemoria(p_action_id, v_act.user_id, NULL);
    RETURN jsonb_build_object('ok', true, 'action', 'completed', 'points', v_points, 'type', 'workflow');
  END IF;

  IF v_act.type = 'single' AND v_act.deadline IS NOT NULL AND p_today > v_act.deadline THEN
    v_points := COALESCE(v_act.late_points, 0);
    v_action := 'completed_late';
  END IF;

  INSERT INTO pv_action_history (user_id, action_id, plant_id, action_title,
                                 from_status, to_status, action, points, occurrence_date)
  VALUES (v_act.user_id, p_action_id, v_act.plant_id, v_act.title,
          v_from_status, 'completed', v_action, v_points, v_occ_date);

  IF v_act.type = 'single' THEN
    UPDATE pv_actions SET status = 'terminated', last_completed_date = v_completed_date
     WHERE id = p_action_id;
    v_finita := true;

  ELSIF v_act.type = 'simple_recurring' THEN
    v_next_ts := COALESCE(v_act.next_occurrence_date, v_act.start_date, now())
                 + (COALESCE(v_act.repeat_after_days, 7) || ' days')::interval;
    UPDATE pv_actions
       SET status = 'completed', last_completed_date = v_completed_date, next_occurrence_date = v_next_ts
     WHERE id = p_action_id;

  ELSIF v_act.type = 'recurring' THEN
    v_next_date := pv_action_next_recurring_date(
      v_act, COALESCE(v_act.next_occurrence_date, v_act.start_date)::date);
    IF v_next_date IS NOT NULL THEN
      v_next_ts := v_next_date::timestamptz + v_time_of_day;
    END IF;
    UPDATE pv_actions
       SET status = CASE WHEN v_next_date IS NULL THEN 'terminated' ELSE 'completed' END,
           last_completed_date = v_completed_date, next_occurrence_date = v_next_ts
     WHERE id = p_action_id;
    v_finita := v_next_date IS NULL;

  ELSIF v_act.type = 'multiple' THEN
    SELECT array_agg(d ORDER BY d) INTO v_dates FROM unnest(v_act.multiple_dates) AS d;
    -- ⚠️ ::date::text dà 'YYYY-MM-DD'; split_part(...,'T',1) no (Postgres usa lo spazio).
    v_cur_str := COALESCE(v_act.next_occurrence_date::date::text, '');
    FOR j IN 1..COALESCE(array_length(v_dates, 1), 0) LOOP
      IF v_dates[j] = v_cur_str THEN v_cur_idx := j; EXIT; END IF;
    END LOOP;
    IF v_cur_idx IS NOT NULL AND v_cur_idx < array_length(v_dates, 1) THEN
      v_next_ts := v_dates[v_cur_idx + 1]::date::timestamptz + v_time_of_day;
    END IF;
    UPDATE pv_actions
       SET status = CASE WHEN v_next_ts IS NULL THEN 'terminated' ELSE 'completed' END,
           last_completed_date = v_completed_date, next_occurrence_date = v_next_ts
     WHERE id = p_action_id;
    v_finita := v_next_ts IS NULL;

  ELSE -- free_repeat
    UPDATE pv_actions SET status = 'completed', last_completed_date = v_completed_date
     WHERE id = p_action_id;
  END IF;

  IF v_finita THEN
    INSERT INTO pv_action_history (user_id, action_id, plant_id, action_title,
                                   from_status, to_status, action, points, occurrence_date)
    VALUES (v_act.user_id, p_action_id, v_act.plant_id, v_act.title,
            'completed', 'terminated', 'terminated', 0, v_occ_date);
    PERFORM pv__sposta_promemoria(p_action_id, v_act.user_id, NULL);
  ELSIF v_next_ts IS NOT NULL THEN
    PERFORM pv__sposta_promemoria(p_action_id, v_act.user_id, v_next_ts);
  END IF;

  RETURN jsonb_build_object('ok', true, 'action', v_action, 'points', v_points,
                            'type', v_act.type, 'next', v_next_ts);
END;
$$;

-- ---------------------------------------------------------------------------
-- Il salto — come task_skip. Non si saltano free_repeat e workflow.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION pv_action_skip_core(
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
  v_act         pv_actions%ROWTYPE;
  v_points      integer;
  v_next_date   date;
  v_next_ts     timestamptz;
  v_time_of_day interval;
  v_dates       text[];
  v_cur_str     text;
  v_cur_idx     integer := NULL;
  j             integer;
  v_occ_date    date;
BEGIN
  SELECT * INTO v_act FROM pv_actions WHERE id = p_action_id AND user_id = p_user;
  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'error', 'azione non trovata');
  END IF;
  IF v_act.type IN ('free_repeat', 'workflow') THEN
    RETURN jsonb_build_object('ok', false, 'error', 'questo tipo non si può saltare');
  END IF;

  v_occ_date    := COALESCE(v_act.next_occurrence_date, v_act.start_date)::date;
  v_points      := COALESCE(v_act.skip_points, 0);
  v_time_of_day := COALESCE(v_act.start_date, now())
                   - date_trunc('day', COALESCE(v_act.start_date, now()));

  INSERT INTO pv_action_history (user_id, action_id, plant_id, action_title,
                                 from_status, to_status, action, points, occurrence_date)
  VALUES (v_act.user_id, p_action_id, v_act.plant_id, v_act.title,
          v_act.status, 'skipped', 'skipped', v_points, v_occ_date);

  IF v_act.type = 'single' THEN
    v_next_ts := COALESCE(v_act.next_occurrence_date, v_act.start_date, now())
                 + (GREATEST(COALESCE(p_days, 1), 1) || ' days')::interval;
    UPDATE pv_actions SET status = 'skipped', next_occurrence_date = v_next_ts WHERE id = p_action_id;

  ELSIF v_act.type = 'simple_recurring' THEN
    v_next_ts := COALESCE(v_act.next_occurrence_date, v_act.start_date, now())
                 + (COALESCE(v_act.repeat_after_days, 7) || ' days')::interval;
    UPDATE pv_actions SET status = 'skipped', next_occurrence_date = v_next_ts WHERE id = p_action_id;

  ELSIF v_act.type = 'recurring' THEN
    v_next_date := pv_action_next_recurring_date(
      v_act, COALESCE(v_act.next_occurrence_date, v_act.start_date)::date);
    IF v_next_date IS NULL THEN
      RAISE EXCEPTION 'impossibile calcolare la prossima occorrenza';
    END IF;
    v_next_ts := v_next_date::timestamptz + v_time_of_day;
    UPDATE pv_actions SET status = 'skipped', next_occurrence_date = v_next_ts WHERE id = p_action_id;

  ELSE -- multiple
    SELECT array_agg(d ORDER BY d) INTO v_dates FROM unnest(v_act.multiple_dates) AS d;
    v_cur_str := COALESCE(v_act.next_occurrence_date::date::text, '');
    FOR j IN 1..COALESCE(array_length(v_dates, 1), 0) LOOP
      IF v_dates[j] = v_cur_str THEN v_cur_idx := j; EXIT; END IF;
    END LOOP;
    IF v_cur_idx IS NOT NULL AND v_cur_idx < array_length(v_dates, 1) THEN
      v_next_ts := v_dates[v_cur_idx + 1]::date::timestamptz + v_time_of_day;
    END IF;
    UPDATE pv_actions
       SET status = CASE WHEN v_next_ts IS NULL THEN 'terminated' ELSE 'skipped' END,
           next_occurrence_date = v_next_ts
     WHERE id = p_action_id;
    IF v_next_ts IS NULL THEN
      INSERT INTO pv_action_history (user_id, action_id, plant_id, action_title,
                                     from_status, to_status, action, points, occurrence_date)
      VALUES (v_act.user_id, p_action_id, v_act.plant_id, v_act.title,
              'skipped', 'terminated', 'terminated', 0, v_occ_date);
    END IF;
  END IF;

  PERFORM pv__sposta_promemoria(p_action_id, v_act.user_id, v_next_ts);

  RETURN jsonb_build_object('ok', true, 'action', 'skipped', 'points', v_points,
                            'type', v_act.type, 'next', v_next_ts);
END;
$$;

-- ── le porte ────────────────────────────────────────────────────────────────
CREATE OR REPLACE FUNCTION pv_action_complete(p_action_id uuid, p_today date DEFAULT CURRENT_DATE)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
  IF auth.uid() IS NULL THEN
    RETURN jsonb_build_object('ok', false, 'error', 'serve un login');
  END IF;
  RETURN pv_action_complete_core(p_action_id, auth.uid(), p_today);
END;
$$;

CREATE OR REPLACE FUNCTION pv_action_skip(p_action_id uuid, p_days integer DEFAULT 1)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
  IF auth.uid() IS NULL THEN
    RETURN jsonb_build_object('ok', false, 'error', 'serve un login');
  END IF;
  RETURN pv_action_skip_core(p_action_id, auth.uid(), p_days);
END;
$$;

-- Smart Blocker non ha un login: parla con la anon key, come quando chiama
-- task_complete. ⚠️ La guardia è che quell'azione abbia DAVVERO un blocco in
-- coda sul canale smart_block: senza, la anon key basterebbe a completare
-- qualunque azione di cui si conosca l'id.
CREATE OR REPLACE FUNCTION pv_smart_block_complete(p_action_id uuid, p_today date DEFAULT CURRENT_DATE)
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
   WHERE q.app = 'plants' AND q.channel = 'smart_block'
     AND q.entity_id::text = p_action_id::text
     AND q.status IN ('pending', 'sent')
   LIMIT 1;
  IF v_user IS NULL THEN
    RETURN jsonb_build_object('ok', false, 'error', 'nessun blocco per questa azione');
  END IF;
  RETURN pv_action_complete_core(p_action_id, v_user, p_today);
END;
$$;

REVOKE ALL ON FUNCTION pv_action_complete_core(uuid, uuid, date) FROM PUBLIC;
REVOKE ALL ON FUNCTION pv_action_skip_core(uuid, uuid, integer)  FROM PUBLIC;
REVOKE ALL ON FUNCTION pv__sposta_promemoria(uuid, uuid, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION pv_action_complete_core(uuid, uuid, date) TO service_role;
GRANT EXECUTE ON FUNCTION pv_action_skip_core(uuid, uuid, integer)  TO service_role;
GRANT EXECUTE ON FUNCTION pv_action_complete(uuid, date)       TO authenticated;
GRANT EXECUTE ON FUNCTION pv_action_skip(uuid, integer)        TO authenticated;
GRANT EXECUTE ON FUNCTION pv_smart_block_complete(uuid, date)  TO anon, authenticated;

-- ── un'azione cancellata si porta via i suoi promemoria ─────────────────────
-- In un trigger e non nei client: la cancellano il web, il nativo e la
-- cascata di una pianta eliminata, e una regola rimasta orfana suonerebbe per
-- un'azione che non c'è più.
CREATE OR REPLACE FUNCTION pv_actions_togli_promemoria()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
  PERFORM pv__sposta_promemoria(OLD.id, OLD.user_id, NULL);
  RETURN OLD;
END;
$$;

DROP TRIGGER IF EXISTS trg_pv_actions_togli_promemoria ON pv_actions;
CREATE TRIGGER trg_pv_actions_togli_promemoria
  AFTER DELETE ON pv_actions
  FOR EACH ROW EXECUTE FUNCTION pv_actions_togli_promemoria();

-- ── la bolla in AppSphere ───────────────────────────────────────────────────
-- Il numero sono i PUNTI delle azioni (successo +10, salto −2, in ritardo −2),
-- quindi conta_punti resta al suo DEFAULT true.
DO $$
DECLARE
  v_colore text;
BEGIN
  IF EXISTS (SELECT 1 FROM cm_apps WHERE html_file = 'piante.html') THEN
    RETURN;
  END IF;
  -- ⚠️ Il colore deve essere DISTINTO: il codice della modalità nascosta è una
  -- sequenza di colori delle bolle.
  SELECT c INTO v_colore
    FROM unnest(ARRAY['#7CB342', '#558B2F', '#9CCC65', '#2E7D32', '#AED581'])
         WITH ORDINALITY AS t(c, n)
   WHERE NOT EXISTS (SELECT 1 FROM cm_apps a WHERE upper(trim(a.color)) = upper(c))
   ORDER BY n
   LIMIT 1;

  INSERT INTO cm_apps (title, description, score_query, color, active, html_file, riservato)
  VALUES ('Piante',
          'Diario di cura delle piante',
          $q$SELECT COALESCE(SUM(points), 0)::int FROM pv_action_history WHERE user_id = auth.uid()$q$,
          v_colore, true, 'piante.html', false);
END;
$$;
