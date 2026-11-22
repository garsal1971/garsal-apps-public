-- =====================================================================
-- Abituati: 💉 resuscitare dalla LISTA DEGLI ARCHIVIATI
--
-- Un ciclo chiuso per jolly esauriti (`hb_chiudi_stack`, reason
-- 'jolly_esauriti') perde la riga di hb_habits: le spunte restano
-- (hb_completions non ha chiave esterna), ma frequenza, giorni, orari,
-- obiettivo, jolly e punti no. Da qui in poi l'archivio porta con sé la
-- scheda intera (`scheda`, la riga di hb_habits com'era); per i cicli già
-- archiviati, che non ce l'hanno, la scheda la manda la pagina dal form.
--
-- La resurrezione:
--   * ricrea la riga di hb_habits con lo STESSO id (le spunte di allora si
--     riagganciano da sé) e la stessa data d'inizio;
--   * i jolly comprati devono coprire anche i giorni fra la chiusura e
--     ieri che resterebbero senza spunta: minimo = fallimenti − jolly + 1;
--   * paga ceil(points_penalty / goal × jolly) in hb_resurrezioni;
--   * TOGLIE la riga d'archivio, e con lei la penalità del fallimento.
--
-- ⚠️ Il conto si fa con la riga già ricreata (le funzioni hb_* la leggono),
-- quindi l'anteprima (p_conferma = false) lavora in un sottoblocco che si
-- annulla da sé alzando un'eccezione: niente resta scritto.
-- =====================================================================

ALTER TABLE public.hb_archived_stacks ADD COLUMN IF NOT EXISTS scheda jsonb;

COMMENT ON COLUMN public.hb_archived_stacks.scheda IS
  'La riga di hb_habits al momento della chiusura (NULL = archiviata prima del 22/11/2026). Serve a resuscitare il ciclo.';

-- ── hb_chiudi_stack: identica a 20260815120000, più la scheda ─────────
CREATE OR REPLACE FUNCTION public.hb_chiudi_stack(
  p_habit_id     uuid,
  p_oggi         date DEFAULT CURRENT_DATE,
  p_nuovo_inizio date DEFAULT NULL
)
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
DECLARE
  v_h      hb_habits%ROWTYPE;
  v_oggi   date := COALESCE(p_oggi, CURRENT_DATE);
  v_inizio date;
  v_streak integer;
  v_gia    integer;
  v_nuovo  uuid;
BEGIN
  IF NOT public.is_garsal() THEN
    RETURN jsonb_build_object('ok', false, 'error', 'non autorizzato');
  END IF;

  SELECT * INTO v_h FROM hb_habits WHERE id = p_habit_id;
  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'error', 'abitudine non trovata');
  END IF;

  v_inizio := COALESCE(v_h.started_at::date, v_h.created_at::date, v_oggi);
  v_streak := hb_streak(p_habit_id, v_oggi);

  SELECT count(*) INTO v_gia
    FROM hb_archived_stacks a
   WHERE a.habit_id = p_habit_id AND a.started_at = v_h.started_at;

  IF v_gia = 0 THEN
    INSERT INTO hb_archived_stacks (
      habit_id, habit_name, category_id, started_at, ended_at,
      final_streak, total_days, total_completions, total_failures,
      points_earned, reason, scheda
    ) VALUES (
      v_h.id, v_h.name, v_h.category_id, v_h.started_at, v_oggi,
      v_streak, (v_oggi - v_inizio),
      (SELECT count(*) FROM hb_completions c
        WHERE c.habit_id = p_habit_id AND c.status = 'completed'
          AND c.completed_at::date >= v_inizio),
      (SELECT count(*) FROM hb_completions c
        WHERE c.habit_id = p_habit_id AND c.status IN ('failed', 'missed')
          AND c.completed_at::date >= v_inizio),
      -COALESCE(v_h.points_penalty, 0), 'jolly_esauriti', to_jsonb(v_h)
    );
  ELSE
    UPDATE hb_archived_stacks
       SET scheda = to_jsonb(v_h)
     WHERE habit_id = p_habit_id AND started_at = v_h.started_at AND scheda IS NULL;
  END IF;

  IF p_nuovo_inizio IS NOT NULL THEN
    v_nuovo := hb_clona(p_habit_id, p_nuovo_inizio);
    UPDATE cm_notification_rules
       SET entity_id = v_nuovo, entity_title = v_h.name
     WHERE app = 'habits' AND entity_id = p_habit_id;
  ELSE
    DELETE FROM cm_notification_rules WHERE app = 'habits' AND entity_id = p_habit_id;
  END IF;

  DELETE FROM hb_habits WHERE id = p_habit_id;

  RETURN jsonb_build_object(
    'ok',         true,
    'archiviato', (v_gia = 0),
    'punti',      -COALESCE(v_h.points_penalty, 0),
    'nuovo_id',   v_nuovo
  );
END;
$$;

-- ── I fallimenti che la riga ricreata avrebbe a oggi ──────────────────
-- Quelli già scritti (failed + missed) più i periodi dovuti, dalla chiusura
-- a ieri, che non hanno nessuna riga e che hb_reconcile marcherebbe mancati.
-- count_window: i pallini rossi delle finestre chiuse, contati dall'inizio.
CREATE OR REPLACE FUNCTION public.hb__fallimenti_alla_ripresa(
  p_habit_id uuid, p_dal date, p_oggi date
)
RETURNS integer
LANGUAGE plpgsql STABLE SECURITY INVOKER SET search_path = public
AS $$
DECLARE
  v_h      hb_habits%ROWTYPE;
  v_giorno date;
  v_fine   date;
  v_giorni text[];
  v_dovuto boolean;
  v_conta  integer := 0;
BEGIN
  SELECT * INTO v_h FROM hb_habits WHERE id = p_habit_id;
  IF NOT FOUND THEN RETURN 0; END IF;

  IF v_h.frequency = 'count_window' THEN
    RETURN hb_giorni_da_recuperare(p_habit_id, v_h.started_at, p_oggi);
  END IF;

  v_conta  := hb_fallimenti(p_habit_id);
  v_giorni := hb_lista(to_jsonb(v_h) -> 'weekdays');
  v_fine   := LEAST(p_oggi - 1, v_h.started_at + GREATEST(COALESCE(v_h.goal, 1), 1) - 1);
  v_giorno := GREATEST(p_dal, v_h.started_at);

  WHILE v_giorno <= v_fine LOOP
    v_dovuto := CASE
      WHEN v_h.frequency IN ('daily', 'daily_multiple') THEN true
      WHEN v_h.frequency = 'weekly' THEN (EXTRACT(dow FROM v_giorno)::int)::text = ANY (v_giorni)
      ELSE false
    END;
    IF v_dovuto AND NOT hb_giorno_risolto(p_habit_id, v_giorno) THEN
      v_conta := v_conta + 1;
    END IF;
    v_giorno := v_giorno + 1;
  END LOOP;

  RETURN v_conta;
END;
$$;

-- ── La resurrezione dall'archivio ─────────────────────────────────────
-- p_conferma = false: anteprima (minimo, costo), non scrive niente.
-- p_scheda: obbligatoria solo se l'archivio non ha la sua.
CREATE OR REPLACE FUNCTION public.hb_resuscita_archivio(
  p_archivio_id uuid,
  p_jolly       integer,
  p_scheda      jsonb   DEFAULT NULL,
  p_oggi        date    DEFAULT CURRENT_DATE,
  p_conferma    boolean DEFAULT false
)
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
DECLARE
  v_a      hb_archived_stacks%ROWTYPE;
  v_oggi   date := COALESCE(p_oggi, CURRENT_DATE);
  v_scheda jsonb;
  v_h      hb_habits%ROWTYPE;
  v_base   integer;
  v_fall   integer;
  v_minimo integer;
  v_punti  integer;
  v_jolly  integer := GREATEST(COALESCE(p_jolly, 0), 0);
BEGIN
  IF NOT public.is_garsal() THEN
    RETURN jsonb_build_object('ok', false, 'error', 'non autorizzato');
  END IF;

  SELECT * INTO v_a FROM hb_archived_stacks WHERE id = p_archivio_id;
  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'error', 'ciclo archiviato non trovato');
  END IF;
  IF v_a.reason IS DISTINCT FROM 'jolly_esauriti' THEN
    RETURN jsonb_build_object('ok', false, 'error', 'si resuscita solo un ciclo finito per jolly esauriti');
  END IF;
  IF v_a.habit_id IS NULL OR EXISTS (SELECT 1 FROM hb_habits WHERE id = v_a.habit_id) THEN
    RETURN jsonb_build_object('ok', false, 'error', 'questa abitudine esiste già fra quelle attive');
  END IF;

  v_scheda := COALESCE(v_a.scheda, p_scheda);
  IF v_scheda IS NULL THEN
    RETURN jsonb_build_object('ok', false, 'error', 'scheda mancante', 'serve_scheda', true);
  END IF;

  v_h := jsonb_populate_record(NULL::hb_habits, v_scheda);
  v_h.id               := v_a.habit_id;
  v_h.name             := COALESCE(NULLIF(btrim(v_h.name), ''), v_a.habit_name);
  v_h.category_id      := COALESCE(v_h.category_id, v_a.category_id);
  v_h.started_at       := v_a.started_at;
  v_h.status           := 'active';
  v_h.frequency        := COALESCE(v_h.frequency, 'daily');
  v_h.goal             := COALESCE(v_h.goal, 30);
  v_h.max_failures     := COALESCE(v_h.max_failures, 3);
  v_h.points_reward    := COALESCE(v_h.points_reward, 0);
  v_h.points_penalty   := COALESCE(v_h.points_penalty, 0);
  v_h.riservato        := COALESCE(v_h.riservato, false);
  v_h.created_at       := COALESCE(v_h.created_at, now());
  v_h.updated_at       := now();
  v_h.current_failures := 0;
  v_base := v_h.max_failures;

  -- Il conto vuole la riga in tabella: anteprima in un sottoblocco annullato.
  IF NOT p_conferma THEN
    BEGIN
      INSERT INTO hb_habits SELECT (v_h).*;
      v_fall := hb__fallimenti_alla_ripresa(v_h.id, v_a.ended_at, v_oggi);
      RAISE EXCEPTION 'hb_anteprima';
    EXCEPTION WHEN raise_exception THEN
      IF SQLERRM <> 'hb_anteprima' THEN RAISE; END IF;
    END;
  ELSE
    INSERT INTO hb_habits SELECT (v_h).*;
    v_fall := hb__fallimenti_alla_ripresa(v_h.id, v_a.ended_at, v_oggi);
  END IF;

  v_minimo := GREATEST(1, v_fall - v_base + 1);
  v_punti  := CEIL(v_h.points_penalty::numeric / GREATEST(v_h.goal, 1) * v_jolly)::int;

  IF NOT p_conferma THEN
    RETURN jsonb_build_object(
      'ok', true, 'anteprima', true,
      'fallimenti', v_fall, 'jolly', v_base, 'minimo', v_minimo,
      'per_jolly', v_h.points_penalty::numeric / GREATEST(v_h.goal, 1),
      'punti', v_punti, 'penalita_tolta', -COALESCE(v_a.points_earned, 0)
    );
  END IF;

  IF v_jolly < v_minimo THEN
    RAISE EXCEPTION 'servono almeno % jolly per uscire dal game over', v_minimo;
  END IF;

  UPDATE hb_habits
     SET max_failures = v_base + v_jolly, current_failures = v_fall
   WHERE id = v_h.id;

  INSERT INTO hb_resurrezioni (habit_id, habit_name, jolly_aggiunti, punti)
  VALUES (v_h.id, v_h.name, v_jolly, v_punti);

  DELETE FROM hb_archived_stacks WHERE id = p_archivio_id;

  RETURN jsonb_build_object(
    'ok', true, 'habit_id', v_h.id,
    'jolly', v_base + v_jolly, 'punti', v_punti
  );
END;
$$;

REVOKE EXECUTE ON FUNCTION public.hb__fallimenti_alla_ripresa(uuid, date, date)               FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION public.hb_resuscita_archivio(uuid, integer, jsonb, date, boolean) FROM PUBLIC;
GRANT  EXECUTE ON FUNCTION public.hb_resuscita_archivio(uuid, integer, jsonb, date, boolean) TO authenticated;
GRANT  EXECUTE ON FUNCTION public.hb_chiudi_stack(uuid, date, date)                           TO authenticated;
