-- Integrazione Smart Block per Ta Firi? — blocco telefono Android (app='ta_firi')
-- Mantiene invariato il meccanismo usato da tasks.html (channel='smart_block', app='tasks').
--
-- Chiamata da:
--  - ta-firi.html: bottoni "Fatto"/"Non fatto" del check-in giornaliero (p_status esplicito)
--  - app Android smartblocker: sblocco col PIN, sempre p_status='done' (stesso comportamento di task_complete)
--
-- Ad ogni check-in, se la sfida non è ancora terminata, sposta in avanti la regola
-- cm_notification_rules (channel='smart_block') al check-in del giorno successivo;
-- altrimenti elimina la regola (sfida conclusa).
CREATE OR REPLACE FUNCTION sf_checkin_set(
  p_challenge_id uuid,
  p_status       text,
  p_today        date DEFAULT CURRENT_DATE
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_challenge sf_challenges%ROWTYPE;
  v_next_date date;
  v_next_ts   timestamptz;
BEGIN
  IF p_status NOT IN ('done', 'not_done') THEN
    RETURN jsonb_build_object('ok', false, 'error', 'stato non valido');
  END IF;

  SELECT * INTO v_challenge FROM sf_challenges WHERE id = p_challenge_id;

  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'error', 'sfida non trovata');
  END IF;

  IF v_challenge.status != 'active' THEN
    RETURN jsonb_build_object('ok', false, 'error', 'sfida non attiva');
  END IF;

  UPDATE sf_checkins
     SET status = p_status, checked_at = now()
   WHERE challenge_id = p_challenge_id AND day_date = p_today;

  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'error', 'giorno non trovato per questa sfida');
  END IF;

  v_next_date := p_today + 1;

  IF v_next_date <= v_challenge.end_date THEN
    -- checkin_time è un orario Europe/Rome; AT TIME ZONE converte in UTC gestendo il DST.
    v_next_ts := (v_next_date + v_challenge.checkin_time) AT TIME ZONE 'Europe/Rome';

    UPDATE cm_notification_rules
       SET reminder_presets = reminder_presets || jsonb_build_object('due_at', v_next_ts)
     WHERE entity_id = p_challenge_id AND app = 'ta_firi' AND channel = 'smart_block';
  ELSE
    DELETE FROM cm_notification_rules
     WHERE entity_id = p_challenge_id AND app = 'ta_firi' AND channel = 'smart_block';
  END IF;

  RETURN jsonb_build_object('ok', true, 'status', p_status, 'next', v_next_ts);
END;
$$;

-- Rete di sicurezza: alla scadenza (finalize), elimina comunque un'eventuale regola residua.
CREATE OR REPLACE FUNCTION sf_finalize_challenge(p_challenge_id uuid)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_challenge   sf_challenges%ROWTYPE;
  v_done_days   integer;
  v_final_score integer;
  v_status      text;
BEGIN
  SELECT * INTO v_challenge FROM sf_challenges WHERE id = p_challenge_id AND user_id = auth.uid();

  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'error', 'sfida non trovata');
  END IF;

  IF v_challenge.status != 'active' THEN
    RETURN jsonb_build_object('ok', true, 'already_final', true, 'status', v_challenge.status, 'final_score', v_challenge.final_score);
  END IF;

  IF v_challenge.end_date >= CURRENT_DATE THEN
    RETURN jsonb_build_object('ok', false, 'error', 'sfida non ancora scaduta');
  END IF;

  SELECT COUNT(*) INTO v_done_days FROM sf_checkins WHERE challenge_id = p_challenge_id AND status = 'done';

  IF v_challenge.scoring_type = 'all_or_nothing' THEN
    v_final_score := CASE WHEN v_done_days = v_challenge.duration_days THEN v_challenge.total_points ELSE 0 END;
  ELSE
    v_final_score := ROUND(v_challenge.total_points::numeric * v_done_days / v_challenge.duration_days);
  END IF;

  v_status := CASE WHEN v_final_score > 0 THEN 'completed' ELSE 'failed' END;

  UPDATE sf_challenges
     SET status = v_status, final_score = v_final_score
   WHERE id = p_challenge_id;

  DELETE FROM cm_notification_rules
   WHERE entity_id = p_challenge_id AND app = 'ta_firi' AND channel = 'smart_block';

  RETURN jsonb_build_object('ok', true, 'status', v_status, 'final_score', v_final_score, 'done_days', v_done_days, 'total_days', v_challenge.duration_days);
END;
$$;
