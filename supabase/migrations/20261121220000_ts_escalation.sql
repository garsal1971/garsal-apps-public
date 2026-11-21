-- 🚨 Escalation di un task scaduto (Tasks web v19.32.0, APK nativa).
--
-- Dal pulsante «Escalation» su un task scaduto si sceglie una scadenza massima e quante
-- notifiche Smart Block mandare: arrivano a intervalli DIMEZZATI e l'ultima cade sulla
-- scadenza massima (4 in 15 ore → dopo 8h, 12h, 14h, 15h).
--
-- Nessuna colonna e nessuna tabella nuova:
--   · una regola cm_notification_rules SPENTA (enabled = false, così fill-notification-queue
--     non la tocca e non cancella le sue righe), app 'tasks', canale 'smart_block',
--     entity_id = md5('escalation:' || task)::uuid — non l'id del task, che ha già la sua
--     regola smart_block e il vincolo (user, app, entity, channel) è unico.
--     reminder_presets = {kind:'escalation', task_id, fino, quante};
--   · le righe della coda la citano come rule_id (ON DELETE CASCADE: tolta la regola se ne
--     vanno anche loro), con entity_id = l'id VERO del task, così lo Smart Blocker sbloccando
--     chiama task_complete su quel task come per ogni altro blocco. metadata porta
--     device_token (indirizzo del telefono) ed escalation: true.
--
-- Si ferma da sé quando il task cambia scadenza o stato, o viene cancellato
-- (trg_ts_tasks_ferma_escalation): completare, saltare e fallire passano tutti di lì,
-- da qualunque client — web, nativo, Smart Blocker, Telegram.

CREATE OR REPLACE FUNCTION public.ts__escalation_rule_entity(p_task_id uuid)
RETURNS uuid LANGUAGE sql IMMUTABLE AS $$
  SELECT md5('escalation:' || p_task_id::text)::uuid
$$;

CREATE OR REPLACE FUNCTION public.task_escalation_avvia(p_task_id uuid, p_fino timestamptz, p_quante integer)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
  v_user   uuid := auth.uid();
  v_title  text;
  v_token  text;
  v_rule   uuid;
  v_ent    uuid := public.ts__escalation_rule_entity(p_task_id);
  v_now    timestamptz := now();
  v_tot    double precision;
  v_passo  double precision;
  v_acc    double precision := 0;
  v_fire   timestamptz;
  v_fires  jsonb := '[]'::jsonb;
  k        integer;
BEGIN
  IF v_user IS NULL THEN RETURN jsonb_build_object('ok', false, 'error', 'Serve un login valido'); END IF;
  IF p_quante IS NULL OR p_quante < 1 OR p_quante > 20 THEN
    RETURN jsonb_build_object('ok', false, 'error', 'Le notifiche vanno da 1 a 20');
  END IF;
  IF p_fino IS NULL OR p_fino <= v_now + interval '1 minute' THEN
    RETURN jsonb_build_object('ok', false, 'error', 'La scadenza massima deve essere nel futuro');
  END IF;

  SELECT title INTO v_title FROM ts_tasks WHERE id = p_task_id;
  IF NOT FOUND THEN RETURN jsonb_build_object('ok', false, 'error', 'Task non trovato'); END IF;

  SELECT smart_block_device_token INTO v_token
    FROM cm_user_notification_settings WHERE user_id = v_user LIMIT 1;
  IF v_token IS NULL OR v_token = '' THEN
    RETURN jsonb_build_object('ok', false, 'error', 'Nessun telefono Smart Blocker collegato');
  END IF;

  -- Una sola escalation per task: la nuova sostituisce quella che c'era.
  DELETE FROM cm_notification_rules
   WHERE user_id = v_user AND app = 'tasks' AND channel = 'smart_block' AND entity_id = v_ent;

  INSERT INTO cm_notification_rules (user_id, app, entity_id, entity_type, channel, enabled,
                                     reminder_presets, notification_spec, entity_title)
  VALUES (v_user, 'tasks', v_ent, 'task', 'smart_block', false,
          jsonb_build_object('kind', 'escalation', 'task_id', p_task_id, 'fino', p_fino, 'quante', p_quante),
          '{}'::jsonb, v_title)
  RETURNING id INTO v_rule;

  -- Intervalli dimezzati: d1 = T·2^(N-1)/(2^N − 1), d(k+1) = d(k)/2, la somma è T.
  v_tot   := extract(epoch FROM (p_fino - v_now));
  v_passo := v_tot * power(2, p_quante - 1) / (power(2, p_quante) - 1);
  FOR k IN 1..p_quante LOOP
    v_acc  := v_acc + v_passo;
    v_fire := CASE WHEN k = p_quante THEN p_fino
                   ELSE date_trunc('second', v_now + make_interval(secs => v_acc)) END;
    INSERT INTO cm_notification_queue (rule_id, user_id, app, entity_id, title, body, channel,
                                       fire_at, status, metadata, occurrence_id)
    VALUES (v_rule, v_user, 'tasks', p_task_id::text,
            '🚨 ' || coalesce(v_title, 'Task scaduto'),
            'Escalation ' || k || '/' || p_quante || ' — ' || coalesce(v_title, ''),
            'smart_block', v_fire, 'pending',
            jsonb_build_object('device_token', v_token, 'escalation', true),
            v_rule || ':' || to_char(v_fire AT TIME ZONE 'Europe/Rome', 'YYYY-MM-DD:HH24:MI:SS'));
    v_fires := v_fires || to_jsonb(v_fire);
    v_passo := v_passo / 2;
  END LOOP;

  RETURN jsonb_build_object('ok', true, 'fino', p_fino, 'quante', p_quante, 'fire_at', v_fires);
END $$;

CREATE OR REPLACE FUNCTION public.task_escalation_ferma(p_task_id uuid)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE v_n integer;
BEGIN
  IF auth.uid() IS NULL THEN RETURN jsonb_build_object('ok', false, 'error', 'Serve un login valido'); END IF;
  DELETE FROM cm_notification_rules
   WHERE user_id = auth.uid() AND app = 'tasks' AND channel = 'smart_block'
     AND entity_id = public.ts__escalation_rule_entity(p_task_id);
  GET DIAGNOSTICS v_n = ROW_COUNT;
  RETURN jsonb_build_object('ok', true, 'fermate', v_n);
END $$;

-- Le escalation in corso dell'utente: una riga per task, con le notifiche ancora da mandare.
CREATE OR REPLACE FUNCTION public.task_escalation_stato()
RETURNS jsonb LANGUAGE sql SECURITY DEFINER SET search_path = public STABLE AS $$
  SELECT coalesce(jsonb_agg(jsonb_build_object(
           'task_id',  r.reminder_presets->>'task_id',
           'fino',     r.reminder_presets->>'fino',
           'quante',   (r.reminder_presets->>'quante')::int,
           'rimaste',  (SELECT count(*) FROM cm_notification_queue q
                         WHERE q.rule_id = r.id AND q.status = 'pending'),
           'prossima', (SELECT min(q.fire_at) FROM cm_notification_queue q
                         WHERE q.rule_id = r.id AND q.status = 'pending'))), '[]'::jsonb)
    FROM cm_notification_rules r
   WHERE r.user_id = auth.uid() AND r.app = 'tasks' AND r.channel = 'smart_block'
     AND r.reminder_presets->>'kind' = 'escalation'
$$;

-- Il task si è mosso (completato, saltato, fallito, riprogrammato) o è stato cancellato:
-- l'escalation non ha più ragione d'essere.
CREATE OR REPLACE FUNCTION public.ts__ferma_escalation()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
BEGIN
  IF TG_OP = 'DELETE'
     OR NEW.next_occurrence_date IS DISTINCT FROM OLD.next_occurrence_date
     OR NEW.status IS DISTINCT FROM OLD.status THEN
    DELETE FROM cm_notification_rules
     WHERE app = 'tasks' AND channel = 'smart_block'
       AND entity_id = public.ts__escalation_rule_entity(OLD.id);
  END IF;
  RETURN NULL;
EXCEPTION WHEN OTHERS THEN
  RAISE WARNING 'ts__ferma_escalation: %', SQLERRM;  -- non far fallire il task per questo
  RETURN NULL;
END $$;

DROP TRIGGER IF EXISTS trg_ts_tasks_ferma_escalation ON public.ts_tasks;
CREATE TRIGGER trg_ts_tasks_ferma_escalation
  AFTER UPDATE OR DELETE ON public.ts_tasks
  FOR EACH ROW EXECUTE FUNCTION public.ts__ferma_escalation();

REVOKE ALL ON FUNCTION public.task_escalation_avvia(uuid, timestamptz, integer) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.task_escalation_ferma(uuid) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.task_escalation_stato() FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.ts__ferma_escalation() FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.task_escalation_avvia(uuid, timestamptz, integer) TO authenticated;
GRANT EXECUTE ON FUNCTION public.task_escalation_ferma(uuid) TO authenticated;
GRANT EXECUTE ON FUNCTION public.task_escalation_stato() TO authenticated;
