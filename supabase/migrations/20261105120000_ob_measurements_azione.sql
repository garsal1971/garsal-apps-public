-- Una rilevazione dice di quale AZIONE è.
--
-- Fino a qui `ob_measurements` era una riga per metrica e per giorno, e non
-- portava l'azione: una metrica collegata a più azioni (es. «Come è andata ?»,
-- condivisa fra Mail in inglese, Pronuncia, Corso…) riceveva un valore solo al
-- giorno, e due azioni eseguite lo stesso giorno si sovrascrivevano. Il grafico
-- «per azione» di 📊 Andamento non poteva separarle.
--
-- ⚠️ action_id NULL resta ammesso: una rilevazione fatta dall'elenco delle
-- metriche (nativo) non nasce da un'azione, e le righe di prima non si possono
-- attribuire tutte. L'unicità è (metrica, giorno, azione) con NULLS NOT
-- DISTINCT: due NULL dello stesso giorno restano una riga sola, come prima.

ALTER TABLE ob_measurements
  ADD COLUMN IF NOT EXISTS action_id uuid REFERENCES ob_actions(id) ON DELETE SET NULL;

ALTER TABLE ob_measurements DROP CONSTRAINT IF EXISTS ob_measurements_metric_id_measured_on_key;
DROP INDEX IF EXISTS ob_measurements_metric_id_measured_on_key;
ALTER TABLE ob_measurements
  ADD CONSTRAINT ob_measurements_metrica_giorno_azione
  UNIQUE NULLS NOT DISTINCT (metric_id, measured_on, action_id);

CREATE INDEX IF NOT EXISTS idx_ob_measurements_action ON ob_measurements (action_id);

-- Le righe scritte dall'app Pronuncia si riconoscono dalla nota («Mani libere:
-- …», «Esercizio: …»): vanno all'azione di quel giorno collegata alla metrica
-- e che ha un'esecuzione in quella data, se è UNA sola. Le altre restano NULL:
-- un'attribuzione indovinata sarebbe peggio di nessuna.
UPDATE ob_measurements m
   SET action_id = sub.action_id
  FROM (
    SELECT x.id, min(h.action_id::text)::uuid AS action_id
      FROM ob_measurements x
      JOIN ob_action_metrics l ON l.metric_id = x.metric_id
      JOIN ob_action_history h ON h.action_id = l.action_id
                              AND h.action IN ('completed','completed_late')
                              AND COALESCE(h.occurrence_date, (h.timestamp AT TIME ZONE 'Europe/Rome')::date) = x.measured_on
      JOIN ob_actions a ON a.id = h.action_id
     WHERE x.action_id IS NULL
       AND (x.note ILIKE 'Mani libere:%' OR x.note ILIKE 'Esercizio:%')
       AND a.title ILIKE '%pronuncia%'
     GROUP BY x.id
    HAVING count(DISTINCT h.action_id) = 1
  ) sub
 WHERE m.id = sub.id;

-- Una sola azione collegata ed eseguita quel giorno: la rilevazione è sua.
UPDATE ob_measurements m
   SET action_id = sub.action_id
  FROM (
    SELECT x.id, min(h.action_id::text)::uuid AS action_id
      FROM ob_measurements x
      JOIN ob_action_metrics l ON l.metric_id = x.metric_id
      JOIN ob_action_history h ON h.action_id = l.action_id
                              AND h.action IN ('completed','completed_late')
                              AND COALESCE(h.occurrence_date, (h.timestamp AT TIME ZONE 'Europe/Rome')::date) = x.measured_on
     WHERE x.action_id IS NULL
     GROUP BY x.id
    HAVING count(DISTINCT h.action_id) = 1
  ) sub
 WHERE m.id = sub.id;

-- La firma cambia (un parametro in più): la vecchia va tolta, o resterebbero
-- due funzioni omonime e PostgREST non saprebbe quale chiamare.
DROP FUNCTION IF EXISTS ob_record_measurement(uuid, numeric, date, text, text);

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

  -- SECURITY DEFINER: senza questo controllo basterebbe l'id di un'azione altrui
  IF p_action_id IS NOT NULL
     AND NOT EXISTS (SELECT 1 FROM ob_actions WHERE id = p_action_id AND user_id = auth.uid()) THEN
    RETURN jsonb_build_object('ok', false, 'error', 'azione non trovata');
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

-- Il valore corrente: a parità di giorno, l'ultima scritta.
CREATE OR REPLACE FUNCTION ob_metric_current(p_metric ob_metrics)
RETURNS numeric
LANGUAGE plpgsql
STABLE
SET search_path TO 'public'
AS $function$
DECLARE
  v_value numeric;
BEGIN
  SELECT value INTO v_value
    FROM ob_measurements
   WHERE metric_id = p_metric.id
   ORDER BY measured_on DESC, created_at DESC
   LIMIT 1;

  -- mai rilevata: vale il punto di partenza, cioè lo zero per cento
  RETURN COALESCE(v_value, (ob_metric_scale(p_metric)).da);
END;
$function$;
