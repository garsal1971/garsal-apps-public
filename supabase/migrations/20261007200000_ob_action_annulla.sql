-- ============================================================================
-- Obiettivi — annullare l'ultima esecuzione di un'azione
-- ============================================================================
-- ob_action_undo(p_history_id) toglie un completamento (o un salto) e riporta
-- l'azione a com'era prima: la prossima occorrenza torna al giorno programmato
-- di quella volta (ob_action_history.occurrence_date), lo stato torna a
-- from_status, e la riga di storico se ne va con la sua gemella `terminated`.
--
-- ⚠️ Solo l'ULTIMA esecuzione: annullarne una in mezzo riporterebbe indietro
-- l'azione lasciando dopo di lei esecuzioni che partono da una prossima
-- occorrenza che non c'è più.
-- ⚠️ Non su un workflow: si chiude dai suoi step, e riaprirlo vorrebbe dire
-- decidere quali step riaprire.
-- ⚠️ Non su una riga senza occurrence_date (le più vecchie, nate prima della
-- colonna): non si saprebbe dove riportare l'azione. Le libere ripetizioni
-- un giorno programmato non l'hanno per costruzione, e lì non serve.
-- ⚠️ Le rilevazioni registrate con quel completamento NON si toccano.
-- ⚠️ I promemoria si spostano sulla data ritrovata; se l'azione era conclusa
-- le sue regole erano già state cancellate e non si possono ricostruire da
-- qui (gli anticipi stavano solo lì): la risposta lo dice con
-- `promemoria_persi`, e si rimettono dal form.
-- ============================================================================

CREATE OR REPLACE FUNCTION ob_action_undo(p_history_id uuid)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_h            ob_action_history%ROWTYPE;
  v_act          ob_actions%ROWTYPE;
  v_time_of_day  interval;
  v_next_ts      timestamptz;
  v_last_done    timestamptz;
  v_era_conclusa boolean;
  v_regole       integer;
BEGIN
  IF auth.uid() IS NULL THEN
    RETURN jsonb_build_object('ok', false, 'error', 'serve un login');
  END IF;

  SELECT * INTO v_h FROM ob_action_history
   WHERE id = p_history_id AND user_id = auth.uid()
     AND action IN ('completed', 'completed_late', 'skipped');
  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'error', 'esecuzione non trovata');
  END IF;

  SELECT * INTO v_act FROM ob_actions WHERE id = v_h.action_id AND user_id = auth.uid();
  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'error', 'l''azione non esiste più');
  END IF;

  IF v_act.type = 'workflow' THEN
    RETURN jsonb_build_object('ok', false, 'error', 'un workflow non si riapre da qui');
  END IF;

  IF EXISTS (SELECT 1 FROM ob_action_history
              WHERE action_id = v_h.action_id AND id <> v_h.id
                AND action <> 'terminated' AND timestamp > v_h.timestamp) THEN
    RETURN jsonb_build_object('ok', false, 'error', 'si annulla solo l''ultima esecuzione');
  END IF;

  IF v_act.type <> 'free_repeat' AND v_h.occurrence_date IS NULL THEN
    RETURN jsonb_build_object('ok', false,
      'error', 'questa esecuzione non ha il giorno programmato: non so dove riportare l''azione');
  END IF;

  v_era_conclusa := v_act.status = 'terminated';
  v_time_of_day  := COALESCE(v_act.start_date, now())
                    - date_trunc('day', COALESCE(v_act.start_date, now()));

  IF v_act.type <> 'free_repeat' THEN
    v_next_ts := v_h.occurrence_date::timestamptz + v_time_of_day;
  END IF;

  -- L'ultimo completamento che resta, dopo aver tolto questo.
  IF v_h.action IN ('completed', 'completed_late') THEN
    SELECT COALESCE(occurrence_date::timestamptz, timestamp) INTO v_last_done
      FROM ob_action_history
     WHERE action_id = v_h.action_id AND id <> v_h.id
       AND action IN ('completed', 'completed_late')
     ORDER BY timestamp DESC LIMIT 1;
  ELSE
    v_last_done := v_act.last_completed_date;
  END IF;

  UPDATE ob_actions
     SET status = COALESCE(NULLIF(v_h.from_status, 'terminated'), 'started'),
         next_occurrence_date = CASE WHEN v_act.type = 'free_repeat'
                                     THEN next_occurrence_date ELSE v_next_ts END,
         last_completed_date = v_last_done
   WHERE id = v_act.id;

  DELETE FROM ob_action_history
   WHERE user_id = auth.uid()
     AND (id = v_h.id
          OR (action = 'terminated' AND action_id = v_h.action_id AND timestamp = v_h.timestamp));

  v_regole := 0;
  IF v_act.type <> 'free_repeat' THEN
    PERFORM ob__sposta_promemoria(v_act.id, auth.uid(), v_next_ts);
    SELECT count(*) INTO v_regole FROM cm_notification_rules
     WHERE app = 'objectives' AND entity_id::text = v_act.id::text AND user_id = auth.uid();
  END IF;

  RETURN jsonb_build_object('ok', true, 'action', 'undone', 'points', -v_h.points,
                            'type', v_act.type, 'next', v_next_ts,
                            'promemoria_persi', v_era_conclusa AND v_regole = 0);
END;
$$;

REVOKE ALL ON FUNCTION ob_action_undo(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ob_action_undo(uuid) TO authenticated;
