-- Obiettivi — la metrica si semplifica: due soli TIPI, e nient'altro da scegliere
-- ===========================================================================
-- Prima una metrica chiedeva otto cose (ruolo, tipo fra quattro, baseline,
-- target, unità, direzione, finestra di accumulo, protocollo) più, per la
-- rubrica, una tabella di criteri con i pesi. Erano decisioni prese *prima* di
-- misurare qualunque cosa, e la maggior parte non si usava mai.
--
-- Restano due tipi, che sono i due modi veri di rispondere a «come va?»:
--
--   autovalutazione  — ti dai un voto dentro una scala che scegli tu
--                      (minimo/massimo, di norma 1-10), con scritto accanto
--                      cosa vuol dire votare basso e cosa votare alto;
--   automisurazione  — misuri un numero, con scritto cosa si misura, da dove
--                      parti e dove vuoi arrivare.
--
-- Il RUOLO resta (primary alimenta la barra «risultato» e il semaforo), e con
-- lui restano le milestone agganciate alla metrica primaria.
--
-- ⚠️ Cancella dati: `ob_rubric_criteria` sparisce con i suoi criteri e i pesi,
-- e `ob_measurements.detail` con i punteggi per criterio. Le rilevazioni no:
-- il valore di una rubrica era già la media pesata, e su una scala 1-5 resta
-- quello che era.
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- 1. la scala dell'autovalutazione
-- ---------------------------------------------------------------------------
ALTER TABLE ob_metrics ADD COLUMN IF NOT EXISTS min_value numeric;
ALTER TABLE ob_metrics ADD COLUMN IF NOT EXISTS max_value numeric;

-- `protocol` diceva «il protocollo di misura», che è il gergo che questa
-- semplificazione toglie di mezzo: è il campo di descrizione, e serve a
-- tutt'e due i tipi — come votare per l'una, cosa si misura per l'altra.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.columns
              WHERE table_name = 'ob_metrics' AND column_name = 'protocol')
     AND NOT EXISTS (SELECT 1 FROM information_schema.columns
              WHERE table_name = 'ob_metrics' AND column_name = 'descrizione') THEN
    ALTER TABLE ob_metrics RENAME COLUMN protocol TO descrizione;
  END IF;
END $$;
ALTER TABLE ob_metrics ADD COLUMN IF NOT EXISTS descrizione text DEFAULT '';

-- ---------------------------------------------------------------------------
-- 2. i vincoli vecchi vanno tolti prima di riscrivere i dati
-- ---------------------------------------------------------------------------
ALTER TABLE ob_metrics DROP CONSTRAINT IF EXISTS ob_metrics_period_only_cumulative;
ALTER TABLE ob_metrics DROP CONSTRAINT IF EXISTS ob_metrics_target_ne_baseline;
ALTER TABLE ob_metrics DROP CONSTRAINT IF EXISTS ob_metrics_kind_check;

-- baseline e target diventano facoltativi: un'autovalutazione non ne ha,
-- ha una scala. Il NOT NULL li terrebbe a zero, che è un valore vero e
-- direbbe una cosa falsa.
ALTER TABLE ob_metrics ALTER COLUMN baseline DROP NOT NULL;
ALTER TABLE ob_metrics ALTER COLUMN baseline DROP DEFAULT;
ALTER TABLE ob_metrics ALTER COLUMN target   DROP NOT NULL;

-- ---------------------------------------------------------------------------
-- 3. le metriche esistenti nei due tipi nuovi
--    rubric -> autovalutazione sulla scala 1-5 che aveva davvero (le sue
--    rilevazioni sono medie pesate fra 1 e 5: cambiarle scala le falserebbe)
-- ---------------------------------------------------------------------------
UPDATE ob_metrics
   SET kind      = 'autovalutazione',
       min_value = 1,
       max_value = 5,
       baseline  = NULL,
       target    = NULL,
       unit      = NULL
 WHERE kind = 'rubric';

-- state / cumulative / checklist erano tutt'e tre «un numero che misuro»:
-- cambiava solo come si sommava, che è la complicazione che va via.
UPDATE ob_metrics
   SET kind      = 'automisurazione',
       min_value = NULL,
       max_value = NULL,
       baseline  = COALESCE(baseline, 0),
       target    = COALESCE(target, COALESCE(baseline, 0) + 1)
 WHERE kind IN ('state', 'cumulative', 'checklist');

-- un target uguale alla baseline non normalizza: lo scarto di 1 lascia la
-- riga salvabile e visibile invece di farla rifiutare dal vincolo.
UPDATE ob_metrics SET target = baseline + 1
 WHERE kind = 'automisurazione' AND target = baseline;

-- ---------------------------------------------------------------------------
-- 4. le colonne che non hanno più un lettore
--    direction: la direzione è già scritta in baseline -> target (e in
--               min -> max), tenerla a parte era un secondo modo di dirla,
--               che poteva contraddire il primo
--    period:    serviva solo a kind='cumulative'
--    source_query: automazione mai implementata, mai letta da nessuno
-- ---------------------------------------------------------------------------
ALTER TABLE ob_metrics DROP COLUMN IF EXISTS direction;
ALTER TABLE ob_metrics DROP COLUMN IF EXISTS period;
ALTER TABLE ob_metrics DROP COLUMN IF EXISTS source_query;

-- ---------------------------------------------------------------------------
-- 5. i vincoli nuovi
-- ---------------------------------------------------------------------------
ALTER TABLE ob_metrics ALTER COLUMN kind SET DEFAULT 'autovalutazione';
ALTER TABLE ob_metrics ADD CONSTRAINT ob_metrics_kind_check
  CHECK (kind IN ('autovalutazione', 'automisurazione'));

-- Ogni tipo compila le sue colonne e lascia vuote quelle dell'altro: senza
-- questo vincolo una metrica potrebbe portare una scala *e* un target, e
-- quale dei due comanda sarebbe una scelta arbitraria dentro il codice.
ALTER TABLE ob_metrics ADD CONSTRAINT ob_metrics_scala_per_tipo CHECK (
  (kind = 'autovalutazione'
     AND min_value IS NOT NULL AND max_value IS NOT NULL AND max_value > min_value
     AND baseline IS NULL AND target IS NULL)
  OR
  (kind = 'automisurazione'
     AND baseline IS NOT NULL AND target IS NOT NULL AND target <> baseline
     AND min_value IS NULL AND max_value IS NULL)
);

-- ---------------------------------------------------------------------------
-- 6. quel che restava solo alla rubrica
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS ob_rubric_criteria;
ALTER TABLE ob_measurements DROP COLUMN IF EXISTS detail;

-- ============================================================================
-- RPC
-- ============================================================================

-- ---------------------------------------------------------------------------
-- ob_metric_scale — gli estremi di una metrica, comunque sia fatta.
-- Esiste perché il «da dove a dove» si legge in due posti (valore corrente e
-- avanzamento) e in due colonne diverse a seconda del tipo: scritto due volte
-- sarebbero due formule da tenere allineate.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION ob_metric_scale(
  p_metric  ob_metrics,
  OUT da    numeric,
  OUT a     numeric
)
LANGUAGE sql
IMMUTABLE
SET search_path = public
AS $$
  SELECT CASE WHEN p_metric.kind = 'autovalutazione' THEN p_metric.min_value ELSE p_metric.baseline END,
         CASE WHEN p_metric.kind = 'autovalutazione' THEN p_metric.max_value ELSE p_metric.target   END;
$$;

-- ---------------------------------------------------------------------------
-- ob_record_measurement — unico punto di scrittura di una rilevazione.
-- La firma perde `p_detail`: i punteggi per criterio non esistono più, e con
-- essi la media pesata calcolata qui. Il valore ora arriva sempre dal client,
-- ma per l'autovalutazione va **dentro la scala**: è il server a dirlo, o due
-- client potrebbero ammettere due intervalli diversi.
-- ---------------------------------------------------------------------------
DROP FUNCTION IF EXISTS ob_record_measurement(uuid, numeric, jsonb, date, text);

CREATE OR REPLACE FUNCTION ob_record_measurement(
  p_metric_id   uuid,
  p_value       numeric DEFAULT NULL,
  p_measured_on date    DEFAULT CURRENT_DATE,
  p_note        text    DEFAULT ''
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_metric ob_metrics%ROWTYPE;
BEGIN
  SELECT * INTO v_metric FROM ob_metrics WHERE id = p_metric_id AND user_id = auth.uid();
  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'error', 'metrica non trovata');
  END IF;

  IF p_value IS NULL THEN
    RETURN jsonb_build_object('ok', false, 'error', 'valore mancante');
  END IF;

  IF v_metric.kind = 'autovalutazione'
     AND (p_value < v_metric.min_value OR p_value > v_metric.max_value) THEN
    RETURN jsonb_build_object('ok', false,
      'error', 'voto fuori scala (' || v_metric.min_value || '-' || v_metric.max_value || ')');
  END IF;

  INSERT INTO ob_measurements (user_id, metric_id, measured_on, value, note)
  VALUES (auth.uid(), p_metric_id, p_measured_on, p_value, COALESCE(p_note, ''))
  ON CONFLICT (metric_id, measured_on)
  DO UPDATE SET value = EXCLUDED.value, note = EXCLUDED.note;

  RETURN jsonb_build_object('ok', true, 'value', p_value, 'kind', v_metric.kind, 'measured_on', p_measured_on);
END;
$$;

-- ---------------------------------------------------------------------------
-- ob_metric_current — valore corrente: l'ultima rilevazione.
-- Non c'è più il ramo cumulativo: sommare dentro una finestra e poi azzerare
-- era il terzo modo di leggere la stessa colonna, e nessuna delle metriche
-- esistenti lo usava.
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
  v_value numeric;
BEGIN
  SELECT value INTO v_value
    FROM ob_measurements
   WHERE metric_id = p_metric.id
   ORDER BY measured_on DESC
   LIMIT 1;

  -- mai rilevata: vale il punto di partenza, cioè lo zero per cento
  RETURN COALESCE(v_value, (ob_metric_scale(p_metric)).da);
END;
$$;

-- ---------------------------------------------------------------------------
-- ob_objective_progress — le DUE barre della dashboard, mai fuse in una media.
-- Invariata nella sostanza: cambia solo da dove legge gli estremi della
-- metrica primaria, che ora dipendono dal tipo.
--     (corrente - da) / (a - da)
-- es. autovalutazione 1-10, voto 7 -> (7-1)/(10-1) = 0,67
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
  v_da         numeric;
  v_a          numeric;
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
    SELECT da, a INTO v_da, v_a FROM ob_metric_scale(v_metric);
    v_current := ob_metric_current(v_metric);
    IF v_a IS DISTINCT FROM v_da THEN
      v_result := GREATEST(0, LEAST(1, (v_current - v_da) / (v_a - v_da)));
    END IF;
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
      v_exp_prog := (v_expected - v_da) / (v_a - v_da);
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
