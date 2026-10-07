-- ============================================================================
-- Obiettivi — il quarto tipo di metrica: «Test GoFluent»
-- ============================================================================
-- Il livello del test GoFluent, scelto da una tendina fra dodici gradini:
--   PRE-A1 · A1 · A1+ · A2 · A2+ · B1 · B1+ · B2 · B2+ · C1 · C1+ · C2
-- In archivio il livello è la sua POSIZIONE (PRE-A1 = 1 … C2 = 12): così
-- barra, semaforo e grafici restano la formula di sempre, (corrente − da) /
-- (a − da). L'etichetta la scrivono le app (GOFLUENT_LIVELLI in
-- obiettivi.html, GoFluentLivelli in ObiettiviModel.kt).
--
-- ⚠️ La scala è FISSA (1-12) come quella del test 1-100, e sta negli stessi
-- min_value/max_value. Nessuna colonna nuova: il giudizio scritto usa la
-- colonna ob_measurements.giudizio già aperta per il test.
-- ============================================================================

ALTER TABLE ob_metrics DROP CONSTRAINT IF EXISTS ob_metrics_kind_check;
ALTER TABLE ob_metrics ADD CONSTRAINT ob_metrics_kind_check
  CHECK (kind IN ('autovalutazione', 'automisurazione', 'test', 'gofluent'));

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
  OR
  (kind = 'gofluent'
     AND min_value = 1 AND max_value = 12
     AND baseline IS NULL AND target IS NULL)
);

CREATE OR REPLACE FUNCTION ob_metric_scale(
  p_metric  ob_metrics,
  OUT da    numeric,
  OUT a     numeric
)
LANGUAGE sql
IMMUTABLE
SET search_path = public
AS $$
  SELECT CASE WHEN p_metric.kind IN ('autovalutazione', 'test', 'gofluent') THEN p_metric.min_value ELSE p_metric.baseline END,
         CASE WHEN p_metric.kind IN ('autovalutazione', 'test', 'gofluent') THEN p_metric.max_value ELSE p_metric.target   END;
$$;

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

  IF v_metric.kind IN ('autovalutazione', 'test', 'gofluent')
     AND (p_value < v_metric.min_value OR p_value > v_metric.max_value) THEN
    RETURN jsonb_build_object('ok', false,
      'error', 'valore fuori scala (' || v_metric.min_value || '-' || v_metric.max_value || ')');
  END IF;

  -- un livello è un gradino: a metà fra B1 e B1+ non c'è niente
  IF v_metric.kind = 'gofluent' AND p_value <> trunc(p_value) THEN
    RETURN jsonb_build_object('ok', false, 'error', 'il livello GoFluent dev''essere un gradino intero (1-12)');
  END IF;

  INSERT INTO ob_measurements (user_id, metric_id, measured_on, value, note, giudizio)
  VALUES (auth.uid(), p_metric_id, p_measured_on, p_value, COALESCE(p_note, ''),
          NULLIF(btrim(COALESCE(p_giudizio, '')), ''))
  ON CONFLICT (metric_id, measured_on)
  DO UPDATE SET value = EXCLUDED.value, note = EXCLUDED.note, giudizio = EXCLUDED.giudizio;

  RETURN jsonb_build_object('ok', true, 'value', p_value, 'kind', v_metric.kind, 'measured_on', p_measured_on);
END;
$$;
