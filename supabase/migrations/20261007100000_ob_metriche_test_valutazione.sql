-- ============================================================================
-- Obiettivi — il terzo tipo di metrica: «Test di valutazione»
-- ============================================================================
-- Il punteggio di un test esterno (es. il test di inglese di GoFluent), sempre
-- su una scala fissa da 1 a 100, e accanto un GIUDIZIO scritto che il test dà
-- insieme al numero (es. «B2 – Upper Intermediate»).
--
-- ⚠️ La scala è FISSA e non si sceglie: per questo il vincolo pretende 1 e 100
-- invece di un qualunque minimo < massimo. Sta in min_value/max_value come
-- quella dell'autovalutazione, così ob_metric_scale, la barra e il semaforo la
-- leggono senza un terzo ramo.
--
-- ⚠️ Il giudizio è della RILEVAZIONE, non della metrica: ogni test ne dà uno
-- suo. Colonna a sé e non dentro `note`, che nella finestra delle rilevazioni
-- è una sola per tutte le metriche dell'azione. NULL = nessun giudizio, non
-- stringa vuota.
-- ============================================================================

-- 1. il tipo nuovo ------------------------------------------------------------
ALTER TABLE ob_metrics DROP CONSTRAINT IF EXISTS ob_metrics_kind_check;
ALTER TABLE ob_metrics ADD CONSTRAINT ob_metrics_kind_check
  CHECK (kind IN ('autovalutazione', 'automisurazione', 'test'));

ALTER TABLE ob_metrics DROP CONSTRAINT IF EXISTS ob_metrics_scala_per_tipo;
ALTER TABLE ob_metrics ADD CONSTRAINT ob_metrics_scala_per_tipo CHECK (
  (kind = 'autovalutazione'
     AND min_value IS NOT NULL AND max_value IS NOT NULL AND max_value > min_value
     AND baseline IS NULL AND target IS NULL)
  OR
  (kind = 'automisurazione'
     AND baseline IS NOT NULL AND target IS NOT NULL AND target <> baseline
     AND min_value IS NULL AND max_value IS NULL)
  OR
  (kind = 'test'
     AND min_value = 1 AND max_value = 100
     AND baseline IS NULL AND target IS NULL)
);

-- 2. il giudizio sulla rilevazione --------------------------------------------
ALTER TABLE ob_measurements ADD COLUMN IF NOT EXISTS giudizio text;

-- 3. ob_metric_scale — il test legge la scala come l'autovalutazione ----------
CREATE OR REPLACE FUNCTION ob_metric_scale(
  p_metric  ob_metrics,
  OUT da    numeric,
  OUT a     numeric
)
LANGUAGE sql
IMMUTABLE
SET search_path = public
AS $$
  SELECT CASE WHEN p_metric.kind IN ('autovalutazione', 'test') THEN p_metric.min_value ELSE p_metric.baseline END,
         CASE WHEN p_metric.kind IN ('autovalutazione', 'test') THEN p_metric.max_value ELSE p_metric.target   END;
$$;

-- 4. ob_record_measurement — scala controllata anche sul test, e il giudizio --
-- La firma vecchia (4 argomenti) va tolta: lasciandola accanto alla nuova,
-- PostgREST troverebbe due funzioni per la stessa chiamata. p_giudizio ha un
-- DEFAULT, quindi chi chiama coi soli quattro argomenti di prima funziona.
DROP FUNCTION IF EXISTS ob_record_measurement(uuid, numeric, date, text);

CREATE OR REPLACE FUNCTION ob_record_measurement(
  p_metric_id   uuid,
  p_value       numeric DEFAULT NULL,
  p_measured_on date    DEFAULT CURRENT_DATE,
  p_note        text    DEFAULT '',
  p_giudizio    text    DEFAULT NULL
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

  IF v_metric.kind IN ('autovalutazione', 'test')
     AND (p_value < v_metric.min_value OR p_value > v_metric.max_value) THEN
    RETURN jsonb_build_object('ok', false,
      'error', 'valore fuori scala (' || v_metric.min_value || '-' || v_metric.max_value || ')');
  END IF;

  INSERT INTO ob_measurements (user_id, metric_id, measured_on, value, note, giudizio)
  VALUES (auth.uid(), p_metric_id, p_measured_on, p_value, COALESCE(p_note, ''),
          NULLIF(btrim(COALESCE(p_giudizio, '')), ''))
  ON CONFLICT (metric_id, measured_on)
  DO UPDATE SET value = EXCLUDED.value, note = EXCLUDED.note, giudizio = EXCLUDED.giudizio;

  RETURN jsonb_build_object('ok', true, 'value', p_value, 'kind', v_metric.kind, 'measured_on', p_measured_on);
END;
$$;
