-- ============================================================================
-- Obiettivi — i livelli GoFluent diventano 21, come nella scala di goFLUENT
-- ============================================================================
--   A1.1 A1.2 A1.3 A1.4 · A2.1 … A2.4 · B1.1 … B1.4 · B2.1 … B2.4 ·
--   C1.1 … C1.4 · C2
-- In archivio il livello resta la sua POSIZIONE (A1.1 = 1 … C2 = 21). Il
-- PRE-A1 non c'è più.
--
-- ⚠️ Cambiando la scala cambia il significato delle posizioni già salvate:
-- rilevazioni e milestone delle metriche GoFluent si riportano sul livello
-- nuovo che corrisponde al vecchio (il «+» va al terzo gradino):
--   PRE-A1 1→1 · A1 2→1 · A1+ 3→3 · A2 4→5  · A2+ 5→7  · B1 6→9  · B1+ 7→11
--   B2 8→13 · B2+ 9→15 · C1 10→17 · C1+ 11→19 · C2 12→21
-- Le etichette le scrivono le app (GOFLUENT_LIVELLI in obiettivi.html,
-- GoFluentLivelli in ObiettiviModel.kt), che vanno cambiate insieme.
-- ============================================================================

CREATE OR REPLACE FUNCTION pg_temp.gof_nuovo(v numeric) RETURNS numeric
LANGUAGE sql IMMUTABLE AS $$
  SELECT CASE v
    WHEN 1 THEN 1  WHEN 2 THEN 1  WHEN 3 THEN 3  WHEN 4 THEN 5
    WHEN 5 THEN 7  WHEN 6 THEN 9  WHEN 7 THEN 11 WHEN 8 THEN 13
    WHEN 9 THEN 15 WHEN 10 THEN 17 WHEN 11 THEN 19 WHEN 12 THEN 21
    ELSE v END;
$$;

ALTER TABLE ob_metrics DROP CONSTRAINT IF EXISTS ob_metrics_scala_per_tipo;

-- solo le metriche ancora sulla scala vecchia: la migration non si rifà due volte
UPDATE ob_measurements ms
   SET value = pg_temp.gof_nuovo(ms.value)
  FROM ob_metrics m
 WHERE ms.metric_id = m.id AND m.kind = 'gofluent' AND m.max_value = 12;

-- le milestone parlano della metrica primaria del loro obiettivo
UPDATE ob_milestones mi
   SET expected_value = pg_temp.gof_nuovo(mi.expected_value)
  FROM ob_metrics m
 WHERE m.objective_id = mi.objective_id AND m.role = 'primary'
   AND m.kind = 'gofluent' AND m.max_value = 12
   AND mi.expected_value IS NOT NULL;

UPDATE ob_metrics SET max_value = 21 WHERE kind = 'gofluent';

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
     AND min_value = 1 AND max_value = 21
     AND baseline IS NULL AND target IS NULL)
);

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

  -- un livello è un gradino: a metà fra B1.1 e B1.2 non c'è niente
  IF v_metric.kind = 'gofluent' AND p_value <> trunc(p_value) THEN
    RETURN jsonb_build_object('ok', false, 'error', 'il livello GoFluent dev''essere un gradino intero (1-21)');
  END IF;

  INSERT INTO ob_measurements (user_id, metric_id, measured_on, value, note, giudizio)
  VALUES (auth.uid(), p_metric_id, p_measured_on, p_value, COALESCE(p_note, ''),
          NULLIF(btrim(COALESCE(p_giudizio, '')), ''))
  ON CONFLICT (metric_id, measured_on)
  DO UPDATE SET value = EXCLUDED.value, note = EXCLUDED.note, giudizio = EXCLUDED.giudizio;

  RETURN jsonb_build_object('ok', true, 'value', p_value, 'kind', v_metric.kind, 'measured_on', p_measured_on);
END;
$$;
