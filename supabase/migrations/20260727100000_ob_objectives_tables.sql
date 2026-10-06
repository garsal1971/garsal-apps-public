-- Obiettivi (ob_*) — obiettivi annuali/trimestrali con metriche lagging + leading
-- Gerarchia a due livelli, RLS per utente, rilevazioni via RPC.
-- ---------------------------------------------------------------------------
-- 1. ob_objectives — gerarchia padre (annuale) / figlio (trimestrale)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ob_objectives (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     uuid DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
  parent_id   uuid REFERENCES ob_objectives(id) ON DELETE CASCADE,
  title       text NOT NULL,
  why         text DEFAULT '',              -- "perché": la motivazione, mostrata in dashboard
  horizon     text NOT NULL DEFAULT 'annual'  CHECK (horizon IN ('annual', 'quarterly')),
  start_date  date NOT NULL DEFAULT CURRENT_DATE,
  due_date    date NOT NULL,
  status      text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'achieved', 'missed', 'paused', 'archived')),
  color       text DEFAULT '#6C5CE7',
  sort_order  integer NOT NULL DEFAULT 0,
  created_at  timestamptz NOT NULL DEFAULT now(),
  -- un figlio non può avere figli: gerarchia a due soli livelli
  CONSTRAINT ob_objectives_no_grandchild CHECK (parent_id IS NULL OR horizon = 'quarterly')
);
CREATE INDEX IF NOT EXISTS idx_ob_objectives_user   ON ob_objectives(user_id);
CREATE INDEX IF NOT EXISTS idx_ob_objectives_parent ON ob_objectives(parent_id);
ALTER TABLE ob_objectives ENABLE ROW LEVEL SECURITY;
CREATE POLICY "ob_objectives_own" ON ob_objectives FOR ALL USING (user_id = auth.uid());

-- ---------------------------------------------------------------------------
-- 2. ob_metrics — 1..N per obiettivo
--    role:  primary = il risultato, alimenta la barra "risultato" (max 1 per obiettivo)
--           control = seconda metrica lagging di riscontro (es. il test ripetibile)
--           leading = lo sforzo, il riscontro settimanale
--    kind:  state      -> conta l'ultimo valore (peso, punteggio test)
--           cumulative -> si somma nel periodo (minuti/settimana, € risparmiati)
--           checklist  -> completati su totale (12/40 lezioni)
--           rubric     -> autovalutazione multi-criterio, media pesata
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ob_metrics (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id       uuid DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
  objective_id  uuid NOT NULL REFERENCES ob_objectives(id) ON DELETE CASCADE,
  name          text NOT NULL,
  role          text NOT NULL DEFAULT 'primary' CHECK (role IN ('primary', 'control', 'leading')),
  kind          text NOT NULL DEFAULT 'state'   CHECK (kind IN ('state', 'cumulative', 'checklist', 'rubric')),
  unit          text DEFAULT '',
  direction     text NOT NULL DEFAULT 'increase' CHECK (direction IN ('increase', 'decrease')),
  baseline      numeric NOT NULL DEFAULT 0,
  target        numeric NOT NULL,
  -- COME si misura. Riproposto a schermo a ogni rilevazione: se cambia il
  -- protocollo la serie storica non è più confrontabile.
  protocol      text DEFAULT '',
  -- solo per kind='cumulative': finestra su cui si somma e poi si azzera
  period        text CHECK (period IN ('week', 'month', 'quarter')),
  -- automazione futura: SQL che calcola il valore al posto dell'inserimento manuale
  source_query  text,
  sort_order    integer NOT NULL DEFAULT 0,
  created_at    timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ob_metrics_target_ne_baseline CHECK (target <> baseline),
  CONSTRAINT ob_metrics_period_only_cumulative CHECK (kind = 'cumulative' OR period IS NULL)
);
CREATE INDEX IF NOT EXISTS idx_ob_metrics_objective ON ob_metrics(objective_id);
CREATE INDEX IF NOT EXISTS idx_ob_metrics_user      ON ob_metrics(user_id);
-- Una sola metrica primaria per obiettivo: è quella che alimenta la barra
-- "risultato" e il semaforo. Senza questo vincolo la scelta sarebbe arbitraria.
CREATE UNIQUE INDEX IF NOT EXISTS uq_ob_metrics_one_primary
    ON ob_metrics(objective_id) WHERE role = 'primary';
ALTER TABLE ob_metrics ENABLE ROW LEVEL SECURITY;
CREATE POLICY "ob_metrics_own" ON ob_metrics FOR ALL USING (user_id = auth.uid());

-- ---------------------------------------------------------------------------
-- 3. ob_rubric_criteria — criteri di una metrica kind='rubric'
--    (l'unica parte dello schema che esiste solo per la rubrica)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ob_rubric_criteria (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     uuid DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
  metric_id   uuid NOT NULL REFERENCES ob_metrics(id) ON DELETE CASCADE,
  label       text NOT NULL,
  weight      numeric NOT NULL DEFAULT 1 CHECK (weight > 0),
  sort_order  integer NOT NULL DEFAULT 0,
  created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_ob_rubric_criteria_metric ON ob_rubric_criteria(metric_id);
ALTER TABLE ob_rubric_criteria ENABLE ROW LEVEL SECURITY;
CREATE POLICY "ob_rubric_criteria_own" ON ob_rubric_criteria FOR ALL USING (user_id = auth.uid());

-- ---------------------------------------------------------------------------
-- 4. ob_measurements — le rilevazioni
--    detail: per kind='rubric' i punteggi per criterio {"<criterio_id>": 3, ...}
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ob_measurements (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id      uuid DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
  metric_id    uuid NOT NULL REFERENCES ob_metrics(id) ON DELETE CASCADE,
  measured_on  date NOT NULL DEFAULT CURRENT_DATE,
  value        numeric NOT NULL,
  detail       jsonb NOT NULL DEFAULT '{}'::jsonb,
  note         text DEFAULT '',
  created_at   timestamptz NOT NULL DEFAULT now(),
  UNIQUE (metric_id, measured_on)
);
CREATE INDEX IF NOT EXISTS idx_ob_measurements_metric ON ob_measurements(metric_id, measured_on DESC);
CREATE INDEX IF NOT EXISTS idx_ob_measurements_user   ON ob_measurements(user_id);
ALTER TABLE ob_measurements ENABLE ROW LEVEL SECURITY;
CREATE POLICY "ob_measurements_own" ON ob_measurements FOR ALL USING (user_id = auth.uid());

-- ---------------------------------------------------------------------------
-- 5. ob_milestones — curva attesa contro cui si calcola il semaforo
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ob_milestones (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id         uuid DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
  objective_id    uuid NOT NULL REFERENCES ob_objectives(id) ON DELETE CASCADE,
  label           text DEFAULT '',
  due_date        date NOT NULL,
  expected_value  numeric,
  status          text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'hit', 'missed')),
  created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_ob_milestones_objective ON ob_milestones(objective_id, due_date);
ALTER TABLE ob_milestones ENABLE ROW LEVEL SECURITY;
CREATE POLICY "ob_milestones_own" ON ob_milestones FOR ALL USING (user_id = auth.uid());

-- ---------------------------------------------------------------------------
-- 6. ob_task_links — le azioni restano task veri in tasks.html, solo collegati
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ob_task_links (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id       uuid DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
  objective_id  uuid NOT NULL REFERENCES ob_objectives(id) ON DELETE CASCADE,
  task_id       uuid REFERENCES ts_tasks(id) ON DELETE CASCADE,
  habit_id      uuid REFERENCES hb_habits(id) ON DELETE CASCADE,
  created_at    timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ob_task_links_one_target CHECK (num_nonnulls(task_id, habit_id) = 1),
  UNIQUE (objective_id, task_id),
  UNIQUE (objective_id, habit_id)
);
CREATE INDEX IF NOT EXISTS idx_ob_task_links_objective ON ob_task_links(objective_id);
ALTER TABLE ob_task_links ENABLE ROW LEVEL SECURITY;
CREATE POLICY "ob_task_links_own" ON ob_task_links FOR ALL USING (user_id = auth.uid());

-- ============================================================================
-- RPC
-- ============================================================================

-- ---------------------------------------------------------------------------
-- ob_record_measurement — unico punto di scrittura di una rilevazione.
-- Per kind='rubric' il valore NON viene passato dal client: è la media pesata
-- dei punteggi per criterio in p_detail, calcolata qui.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION ob_record_measurement(
  p_metric_id   uuid,
  p_value       numeric DEFAULT NULL,
  p_detail      jsonb   DEFAULT '{}'::jsonb,
  p_measured_on date    DEFAULT CURRENT_DATE,
  p_note        text    DEFAULT ''
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_metric    ob_metrics%ROWTYPE;
  v_value     numeric;
  v_sum_w     numeric := 0;
  v_sum_wv    numeric := 0;
  v_crit      RECORD;
  v_score     numeric;
BEGIN
  SELECT * INTO v_metric FROM ob_metrics WHERE id = p_metric_id AND user_id = auth.uid();
  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'error', 'metrica non trovata');
  END IF;

  IF v_metric.kind = 'rubric' THEN
    FOR v_crit IN SELECT * FROM ob_rubric_criteria WHERE metric_id = p_metric_id LOOP
      v_score := (p_detail ->> v_crit.id::text)::numeric;
      IF v_score IS NULL THEN
        RETURN jsonb_build_object('ok', false, 'error', 'punteggio mancante per il criterio: ' || v_crit.label);
      END IF;
      IF v_score < 1 OR v_score > 5 THEN
        RETURN jsonb_build_object('ok', false, 'error', 'punteggio fuori scala (1-5) per: ' || v_crit.label);
      END IF;
      v_sum_w  := v_sum_w  + v_crit.weight;
      v_sum_wv := v_sum_wv + v_crit.weight * v_score;
    END LOOP;

    IF v_sum_w = 0 THEN
      RETURN jsonb_build_object('ok', false, 'error', 'la rubrica non ha criteri definiti');
    END IF;

    v_value := ROUND(v_sum_wv / v_sum_w, 2);
  ELSE
    IF p_value IS NULL THEN
      RETURN jsonb_build_object('ok', false, 'error', 'valore mancante');
    END IF;
    v_value := p_value;
  END IF;

  INSERT INTO ob_measurements (user_id, metric_id, measured_on, value, detail, note)
  VALUES (auth.uid(), p_metric_id, p_measured_on, v_value, COALESCE(p_detail, '{}'::jsonb), COALESCE(p_note, ''))
  ON CONFLICT (metric_id, measured_on)
  DO UPDATE SET value = EXCLUDED.value, detail = EXCLUDED.detail, note = EXCLUDED.note;

  RETURN jsonb_build_object('ok', true, 'value', v_value, 'kind', v_metric.kind, 'measured_on', p_measured_on);
END;
$$;

-- ---------------------------------------------------------------------------
-- ob_metric_current — valore corrente di una metrica secondo il suo kind.
--   state / checklist / rubric -> ultima rilevazione
--   cumulative                 -> somma sulla finestra corrente (period)
-- ---------------------------------------------------------------------------
-- NB: volutamente SECURITY INVOKER. Riceve una riga ob_metrics dal chiamante, che
-- potrebbe forgiarla con l'id di un'altra persona: lasciando valere la RLS su
-- ob_measurements la lettura resta confinata alle proprie rilevazioni.
CREATE OR REPLACE FUNCTION ob_metric_current(p_metric ob_metrics)
RETURNS numeric
LANGUAGE plpgsql
STABLE
SET search_path = public
AS $$
DECLARE
  v_from  date;
  v_value numeric;
BEGIN
  IF p_metric.kind = 'cumulative' THEN
    v_from := date_trunc(COALESCE(p_metric.period, 'week'), CURRENT_DATE)::date;
    SELECT COALESCE(SUM(value), 0) INTO v_value
      FROM ob_measurements
     WHERE metric_id = p_metric.id AND measured_on >= v_from;
  ELSE
    SELECT value INTO v_value
      FROM ob_measurements
     WHERE metric_id = p_metric.id
     ORDER BY measured_on DESC
     LIMIT 1;
    v_value := COALESCE(v_value, p_metric.baseline);
  END IF;

  RETURN v_value;
END;
$$;

-- ---------------------------------------------------------------------------
-- ob_objective_progress — le DUE barre della dashboard, mai fuse in una media.
--   result    = avanzamento della metrica propria (primary) dell'obiettivo
--   execution = % di figli + milestone completati (l'esecuzione del piano)
--   status    = semaforo: result confrontato con la curva attesa (milestone)
--
-- La formula di avanzamento è unica per tutti i kind e regge entrambe le
-- direction, perché normalizza sull'intervallo baseline -> target:
--     (corrente - baseline) / (target - baseline)
-- es. pause: baseline 14, target 3, corrente 6 -> (6-14)/(3-14) = 0,73
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION ob_objective_progress(p_objective_id uuid)
RETURNS jsonb
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_obj        ob_objectives%ROWTYPE;
  v_metric     ob_metrics%ROWTYPE;
  v_current    numeric;
  v_result     numeric := NULL;
  v_children   integer;
  v_children_ok integer;
  v_ms_total   integer;
  v_ms_hit     integer;
  v_execution  numeric := NULL;
  v_expected   numeric;
  v_exp_prog   numeric;
  v_status     text := 'unknown';
BEGIN
  SELECT * INTO v_obj FROM ob_objectives WHERE id = p_objective_id AND user_id = auth.uid();
  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'error', 'obiettivo non trovato');
  END IF;

  -- --- barra 1: risultato (metrica propria) ---
  -- unica per costruzione (uq_ob_metrics_one_primary)
  SELECT * INTO v_metric
    FROM ob_metrics
   WHERE objective_id = p_objective_id AND role = 'primary';

  IF FOUND THEN
    v_current := ob_metric_current(v_metric);
    v_result  := GREATEST(0, LEAST(1,
                   (v_current - v_metric.baseline) / (v_metric.target - v_metric.baseline)));
  END IF;

  -- --- barra 2: esecuzione del piano (figli + milestone) ---
  SELECT COUNT(*), COUNT(*) FILTER (WHERE status = 'achieved')
    INTO v_children, v_children_ok
    FROM ob_objectives WHERE parent_id = p_objective_id;

  SELECT COUNT(*), COUNT(*) FILTER (WHERE status = 'hit')
    INTO v_ms_total, v_ms_hit
    FROM ob_milestones WHERE objective_id = p_objective_id;

  IF (v_children + v_ms_total) > 0 THEN
    v_execution := (v_children_ok + v_ms_hit)::numeric / (v_children + v_ms_total);
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
      v_exp_prog := (v_expected - v_metric.baseline) / (v_metric.target - v_metric.baseline);
      v_status := CASE
        WHEN v_result >= v_exp_prog             THEN 'on_track'
        WHEN v_result >= v_exp_prog * 0.75      THEN 'at_risk'
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
    'milestones_hit', v_ms_hit
  );
END;
$$;

-- ---------------------------------------------------------------------------
-- Registrazione nel launcher AppSphere
-- ---------------------------------------------------------------------------
INSERT INTO cm_apps (title, description, score_query, color, active, html_file, riservato)
SELECT 'Obiettivi',
       'Obiettivi annuali e trimestrali con metriche',
       'SELECT COUNT(*)::int FROM ob_objectives WHERE user_id = auth.uid() AND status = ''active''',
       '#6C5CE7', true, 'obiettivi.html', false
WHERE NOT EXISTS (SELECT 1 FROM cm_apps WHERE title = 'Obiettivi');
