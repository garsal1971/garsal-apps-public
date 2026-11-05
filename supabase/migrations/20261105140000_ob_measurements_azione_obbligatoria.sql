-- Una rilevazione esiste solo dentro un'AZIONE.
--
-- Fino a qui action_id era ammesso NULL (rilevazioni fatte dall'elenco delle
-- metriche nell'APK nativa, e righe vecchie non attribuibili): in 📊 Andamento
-- quelle righe non stanno sotto nessuna azione e confondono i conti. Da qui:
--  * le righe senza azione si cancellano;
--  * action_id è NOT NULL;
--  * la FK passa da SET NULL a CASCADE: con NOT NULL «SET NULL» non si può
--    più fare, quindi cancellare un'azione cancella anche le sue rilevazioni;
--  * ob_record_measurement rifiuta una rilevazione senza azione.

DELETE FROM ob_measurements WHERE action_id IS NULL;

ALTER TABLE ob_measurements DROP CONSTRAINT IF EXISTS ob_measurements_action_id_fkey;
ALTER TABLE ob_measurements
  ADD CONSTRAINT ob_measurements_action_id_fkey
  FOREIGN KEY (action_id) REFERENCES ob_actions(id) ON DELETE CASCADE;

ALTER TABLE ob_measurements ALTER COLUMN action_id SET NOT NULL;

CREATE OR REPLACE FUNCTION ob_record_measurement(
  p_metric_id   uuid,
  p_value       numeric DEFAULT NULL,
  p_measured_on date    DEFAULT CURRENT_DATE,
  p_note        text    DEFAULT '',
  p_giudizio    text    DEFAULT NULL,
  p_action_id   uuid    DEFAULT NULL
) RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
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

  -- una rilevazione nasce sempre da un'azione
  IF p_action_id IS NULL THEN
    RETURN jsonb_build_object('ok', false, 'error', 'azione mancante: una rilevazione si registra chiudendo un''azione');
  END IF;

  -- SECURITY DEFINER: senza questo controllo basterebbe l'id di un'azione altrui
  IF NOT EXISTS (SELECT 1 FROM ob_actions WHERE id = p_action_id AND user_id = auth.uid()) THEN
    RETURN jsonb_build_object('ok', false, 'error', 'azione non trovata');
  END IF;

  IF v_metric.kind IN ('autovalutazione', 'test', 'gofluent')
     AND (p_value < v_metric.min_value OR p_value > v_metric.max_value) THEN
    RETURN jsonb_build_object('ok', false,
      'error', 'valore fuori scala (' || v_metric.min_value || '-' || v_metric.max_value || ')');
  END IF;

  IF v_metric.kind = 'gofluent' AND p_value <> trunc(p_value) THEN
    RETURN jsonb_build_object('ok', false, 'error', 'il livello GoFluent dev''essere un gradino intero (1-21)');
  END IF;

  INSERT INTO ob_measurements (user_id, metric_id, measured_on, value, note, giudizio, action_id)
  VALUES (auth.uid(), p_metric_id, p_measured_on, p_value, COALESCE(p_note, ''),
          NULLIF(btrim(COALESCE(p_giudizio, '')), ''), p_action_id)
  ON CONFLICT ON CONSTRAINT ob_measurements_metrica_giorno_azione
  DO UPDATE SET value = EXCLUDED.value, note = EXCLUDED.note, giudizio = EXCLUDED.giudizio;

  RETURN jsonb_build_object('ok', true, 'value', p_value, 'kind', v_metric.kind,
                            'measured_on', p_measured_on, 'action_id', p_action_id);
END;
$function$;

GRANT EXECUTE ON FUNCTION ob_record_measurement(uuid, numeric, date, text, text, uuid) TO authenticated;
