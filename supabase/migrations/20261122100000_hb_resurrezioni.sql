-- =====================================================================
-- Abituati: 💉 resuscitare uno stack finito in game over per jolly esauriti
--
-- Si aggiungono N jolly (`max_failures += N`) pagando una penalità:
--   punti = ceil(points_penalty / goal × N)
-- cioè la penalità del fallimento spalmata sui periodi dello stack, per
-- ogni jolly comprato. N deve bastare a uscire dal game over: il game over
-- scatta con fallimenti >= jolly, quindi il minimo è fallimenti − jolly + 1.
--
-- ⚠️ La penalità sta in una TABELLA a sé e non su hb_habits: quella riga
-- la cancellano hb_chiudi_stack (Ricomincia / Interrompi), lo stack
-- scaduto di hb_reconcile e il pulsante Elimina — e coi punti pagati se ne
-- andrebbe anche la penalità. Qui `habit_id` è ON DELETE SET NULL e il
-- nome è copiato sulla riga.
--
-- Sostituisce il vecchio «🔁 Ripristina» della pagina, che trasformava i
-- `missed` in `completed` e azzerava i jolly gratis.
-- =====================================================================

CREATE TABLE IF NOT EXISTS public.hb_resurrezioni (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  habit_id       uuid REFERENCES public.hb_habits(id) ON DELETE SET NULL,
  habit_name     text,
  jolly_aggiunti integer NOT NULL CHECK (jolly_aggiunti > 0),
  punti          integer NOT NULL CHECK (punti >= 0),
  creato_il      timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE public.hb_resurrezioni ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS "hb_resurrezioni_garsal_only" ON public.hb_resurrezioni;
CREATE POLICY "hb_resurrezioni_garsal_only" ON public.hb_resurrezioni
  FOR ALL USING (public.is_garsal()) WITH CHECK (public.is_garsal());

-- Il costo, in un posto solo: la pagina lo mostra in anteprima chiamando
-- questa, così non ne tiene una seconda copia.
CREATE OR REPLACE FUNCTION public.hb_resurrezione_costo(p_habit_id uuid, p_jolly integer)
RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY INVOKER SET search_path = public
AS $$
DECLARE
  v_h      hb_habits%ROWTYPE;
  v_fall   integer;
  v_minimo integer;
BEGIN
  SELECT * INTO v_h FROM hb_habits WHERE id = p_habit_id;
  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'error', 'abitudine non trovata');
  END IF;

  v_fall   := hb_fallimenti(p_habit_id);
  v_minimo := GREATEST(1, v_fall - COALESCE(v_h.max_failures, 0) + 1);

  RETURN jsonb_build_object(
    'ok',          true,
    'fallimenti',  v_fall,
    'jolly',       COALESCE(v_h.max_failures, 0),
    'minimo',      v_minimo,
    'per_jolly',   COALESCE(v_h.points_penalty, 0)::numeric / GREATEST(COALESCE(v_h.goal, 1), 1),
    'punti',       CEIL(COALESCE(v_h.points_penalty, 0)::numeric
                        / GREATEST(COALESCE(v_h.goal, 1), 1)
                        * GREATEST(COALESCE(p_jolly, 0), 0))::int
  );
END;
$$;

CREATE OR REPLACE FUNCTION public.hb_resuscita(p_habit_id uuid, p_jolly integer)
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
DECLARE
  v_h     hb_habits%ROWTYPE;
  v_costo jsonb;
  v_punti integer;
BEGIN
  IF NOT public.is_garsal() THEN
    RETURN jsonb_build_object('ok', false, 'error', 'non autorizzato');
  END IF;

  SELECT * INTO v_h FROM hb_habits WHERE id = p_habit_id FOR UPDATE;
  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'error', 'abitudine non trovata');
  END IF;
  IF v_h.status IS DISTINCT FROM 'active' THEN
    RETURN jsonb_build_object('ok', false, 'error', 'si resuscita solo uno stack in corso');
  END IF;

  v_costo := hb_resurrezione_costo(p_habit_id, p_jolly);
  IF COALESCE(p_jolly, 0) < (v_costo->>'minimo')::int THEN
    RETURN jsonb_build_object('ok', false,
      'error', 'servono almeno ' || (v_costo->>'minimo') || ' jolly per uscire dal game over');
  END IF;
  v_punti := (v_costo->>'punti')::int;

  UPDATE hb_habits
     SET max_failures = COALESCE(max_failures, 0) + p_jolly
   WHERE id = p_habit_id;

  INSERT INTO hb_resurrezioni (habit_id, habit_name, jolly_aggiunti, punti)
  VALUES (p_habit_id, v_h.name, p_jolly, v_punti);

  RETURN jsonb_build_object(
    'ok',     true,
    'jolly',  COALESCE(v_h.max_failures, 0) + p_jolly,
    'punti',  v_punti
  );
END;
$$;

REVOKE EXECUTE ON FUNCTION public.hb_resuscita(uuid, integer)          FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION public.hb_resurrezione_costo(uuid, integer) FROM PUBLIC;
GRANT  EXECUTE ON FUNCTION public.hb_resuscita(uuid, integer)          TO authenticated;
GRANT  EXECUTE ON FUNCTION public.hb_resurrezione_costo(uuid, integer) TO authenticated;

-- La bolla: le penalità delle resurrezioni si tolgono come quelle dei falliti.
-- ⚠️ Allineati: recalculateTotalScore() in habit-tracker.html e il lettore
-- di ☰ → 🏆 Punti in index.html (PT_LETTORI).
DO $$
BEGIN
  IF to_regclass('public.cm_apps') IS NULL THEN
    RAISE NOTICE 'cm_apps non esiste: score_query di habit-tracker.html non aggiornata.';
    RETURN;
  END IF;

  UPDATE cm_apps
     SET score_query = 'SELECT ('
       || ' - COALESCE((SELECT SUM(points_penalty) FROM hb_habits WHERE status = ''failed''), 0)'
       || ' + COALESCE((SELECT SUM(points_earned) FROM hb_archived_stacks), 0)'
       || ' - COALESCE((SELECT SUM(punti) FROM hb_resurrezioni), 0)'
       || ')::int'
   WHERE html_file = 'habit-tracker.html';
END $$;
