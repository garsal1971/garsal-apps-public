-- Abituati: il web passa alle RPC `hb_*`, e tre cose da sistemare prima.
--
-- 1. `max_failures` NULL diventa 3. Il web l'ha sempre letto così
--    (`max_failures || 3`), le RPC come zero — cioè nessun game over. Da
--    qui in poi le due strade leggono la stessa colonna nello stesso modo,
--    e il form del web non scrive più NULL (casella vuota = 3).
--
-- 2. `hb_clona` copia anche `times_target`, `window_days` e `max_per_day`.
--    Sono arrivati con count_window (`20260915120000_…`, `20260916100000_…`)
--    dopo che `hb_clona` era stata scritta: «Ricomincia» dal telefono su
--    un'abitudine «N volte in M giorni» produceva una copia senza N né M.
--
-- 3. `hb_reconcile` restituisce `regole` — TUTTE le regole di promemoria
--    dell'abitudine — accanto a `regola`, che resta per l'APK già
--    installata. Da quando c'è il canale 'android' le regole sono due, e
--    con la sola prima «Ricomincia» su uno stack scaduto ne perdeva una.
--    Il ramo «completato» le porta anche lui: il web ricrea il ciclo da lì.

-- ── 1. Jolly: vuoto vale 3 ───────────────────────────────────────────
UPDATE hb_habits SET max_failures = 3 WHERE max_failures IS NULL;

-- ── 2. hb_clona ──────────────────────────────────────────────────────
CREATE OR REPLACE FUNCTION public.hb_clona(p_habit_id uuid, p_inizio date)
RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
DECLARE
  v_nuovo uuid;
BEGIN
  INSERT INTO hb_habits (
    name, description, category_id, frequency, weekdays, daily_times,
    times_target, window_days, max_per_day,
    goal, max_failures, points_reward, points_penalty, riservato,
    started_at, status, current_failures
  )
  SELECT h.name, h.description, h.category_id, h.frequency, h.weekdays, h.daily_times,
         h.times_target, h.window_days, h.max_per_day,
         h.goal, h.max_failures, h.points_reward, h.points_penalty, COALESCE(h.riservato, false),
         p_inizio, 'active', 0
    FROM hb_habits h
   WHERE h.id = p_habit_id
  RETURNING id INTO v_nuovo;

  RETURN v_nuovo;
END;
$$;

GRANT EXECUTE ON FUNCTION public.hb_clona(uuid, date) TO authenticated;

-- ── 3. hb_reconcile ──────────────────────────────────────────────────
-- Identica a `20260916120000_hb_count_window_rpc.sql`, più `v_regole`.
CREATE OR REPLACE FUNCTION public.hb_reconcile(p_oggi date DEFAULT CURRENT_DATE)
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
DECLARE
  v_h           hb_habits%ROWTYPE;
  v_oggi        date := COALESCE(p_oggi, CURRENT_DATE);
  v_inizio      date;
  v_giorno      date;
  v_orari       text[];
  v_giorni_sett text[];
  v_orario      text;
  v_key         text;
  v_dovuto      boolean;
  v_mancanti    text[];
  v_fallimenti  integer;
  v_streak      integer;
  v_giorni      integer;
  v_fine        date;
  v_mancati     integer;
  v_coperto     boolean;
  v_regola      jsonb;
  v_regole      jsonb;
  v_gia         integer;
  v_completati  jsonb := '[]'::jsonb;
  v_gameover    jsonb := '[]'::jsonb;
  v_scheda      jsonb;
  v_m           integer;
  v_n           integer;
  v_k           integer;
  v_i           integer;
  v_ws          date;
  v_prefisso    text;
  v_rossi       integer;
BEGIN
  IF NOT public.is_garsal() THEN
    RETURN jsonb_build_object('ok', false, 'error', 'non autorizzato');
  END IF;

  FOR v_h IN SELECT * FROM hb_habits WHERE status = 'active' LOOP
    v_inizio := v_h.started_at::date;
    CONTINUE WHEN v_inizio IS NULL OR v_inizio > v_oggi;

    v_orari       := hb_lista(to_jsonb(v_h) -> 'daily_times');
    v_giorni_sett := hb_lista(to_jsonb(v_h) -> 'weekdays');
    v_m           := GREATEST(1, COALESCE(v_h.window_days, 1));
    v_n           := GREATEST(1, COALESCE(v_h.times_target, 1));

    -- ── 1. I periodi passati senza riga diventano `missed` ────────
    IF v_h.frequency = 'count_window' THEN
      -- ⚠️ Qui il periodo è la FINESTRA, non il giorno: una finestra
      -- chiusa sotto N costa un jolly per ogni pallino rosso, e le
      -- chiavi portano il progressivo (`w2026-09-16#3`) perché
      -- riconoscere quelle già scritte non dipenda dal contarle due
      -- volte. Gemella del ramo count_window di `checkMissedDays()`.
      v_k := 0;
      LOOP
        EXIT WHEN COALESCE(v_h.goal, 0) > 0 AND v_k >= v_h.goal;
        v_ws := v_inizio + v_k * v_m;
        -- Una finestra ancora aperta non ha mancate: c'è tempo.
        EXIT WHEN v_ws + v_m - 1 >= v_oggi;

        v_prefisso := 'w' || v_ws::text;
        SELECT count(*) INTO v_gia
          FROM hb_completions c
         WHERE c.habit_id = v_h.id
           AND c.period_key LIKE v_prefisso || '%';

        v_rossi := GREATEST(0, v_n - hb_cw_fatte(v_h.id, v_ws, v_m));

        FOR v_i IN (v_gia + 1) .. v_rossi LOOP
          INSERT INTO hb_completions (habit_id, completed_at, status, period_key)
          VALUES (v_h.id, v_ws + TIME '12:00', 'missed',
                  v_prefisso || '#' || v_i::text);
        END LOOP;

        v_k := v_k + 1;
        EXIT WHEN v_k > 100000;
      END LOOP;
    ELSE
      v_giorno := v_inizio;
      WHILE v_giorno < v_oggi LOOP
        v_dovuto := CASE
          WHEN v_h.frequency IN ('daily', 'daily_multiple') THEN true
          WHEN v_h.frequency = 'weekly' THEN
            -- I giorni sono numerati alla JavaScript: 0 = domenica.
            (EXTRACT(dow FROM v_giorno)::int)::text = ANY (v_giorni_sett)
          ELSE false
        END;

        IF v_dovuto THEN
          IF v_h.frequency = 'daily_multiple' AND array_length(v_orari, 1) > 0 THEN
            v_mancanti := '{}';
            FOREACH v_orario IN ARRAY v_orari LOOP
              v_key := hb_periodo_key(v_h.frequency, v_giorno, v_orario);
              IF NOT EXISTS (
                SELECT 1 FROM hb_completions c
                 WHERE c.habit_id = v_h.id
                   AND (c.period_key = v_key
                        OR (c.period_key IS NULL
                            AND c.completed_at::date = v_giorno
                            AND to_char(c.completed_at, 'HH24:MI') = v_orario))
              ) THEN
                v_mancanti := v_mancanti || v_key;
              END IF;
            END LOOP;

            -- Una riga per slot mancante, ma il jolly lo conta il giorno:
            -- ci pensa `hb_fallimenti`, che raggruppa per data.
            FOREACH v_key IN ARRAY v_mancanti LOOP
              INSERT INTO hb_completions (habit_id, completed_at, status, period_key)
              VALUES (v_h.id, v_giorno + TIME '12:00', 'missed', v_key);
            END LOOP;
          ELSE
            v_key := hb_periodo_key(v_h.frequency, v_giorno, NULL);
            IF NOT EXISTS (
              SELECT 1 FROM hb_completions c
               WHERE c.habit_id = v_h.id
                 AND (c.period_key = v_key OR c.completed_at::date = v_giorno)
            ) THEN
              INSERT INTO hb_completions (habit_id, completed_at, status, period_key)
              VALUES (v_h.id, v_giorno + TIME '12:00', 'missed', v_key);
            END IF;
          END IF;
        END IF;

        v_giorno := v_giorno + 1;
      END LOOP;
    END IF;

    -- ── 2. I jolly si riallineano al conteggio vero ───────────────
    v_fallimenti := hb_fallimenti(v_h.id);
    IF COALESCE(v_h.current_failures, 0) IS DISTINCT FROM v_fallimenti THEN
      UPDATE hb_habits SET current_failures = v_fallimenti WHERE id = v_h.id;
      v_h.current_failures := v_fallimenti;
    END IF;

    v_streak := hb_streak(v_h.id, v_oggi);
    v_giorni := hb_giorni_fatti(v_h.id, v_oggi);

    v_scheda := to_jsonb(v_h);

    -- Tutte le regole di promemoria, prima che i rami 3 e 4 tocchino la riga.
    SELECT COALESCE(jsonb_agg(to_jsonb(r)), '[]'::jsonb) INTO v_regole
      FROM cm_notification_rules r
     WHERE r.app = 'habits' AND r.entity_id = v_h.id;

    -- ── 3. Obiettivo raggiunto → archivio e stack chiuso ──────────
    IF COALESCE(v_h.goal, 0) > 0 AND (v_streak >= v_h.goal OR v_giorni >= v_h.goal) THEN
      INSERT INTO hb_archived_stacks (
        habit_id, habit_name, category_id, started_at, ended_at,
        final_streak, total_days, total_completions, total_failures,
        points_earned, reason
      ) VALUES (
        v_h.id, v_h.name, v_h.category_id, v_h.started_at, v_oggi,
        GREATEST(v_streak, v_giorni), (v_oggi - v_inizio),
        (SELECT count(*) FROM hb_completions c
          WHERE c.habit_id = v_h.id AND c.status = 'completed'
            AND c.completed_at::date >= v_inizio),
        0,
        COALESCE(v_h.points_reward, 0), 'completato'
      );

      UPDATE hb_habits SET status = 'completed' WHERE id = v_h.id;

      v_completati := v_completati || jsonb_build_object(
        'habit_id', v_h.id,
        'nome',     v_h.name,
        'streak',   GREATEST(v_streak, v_giorni),
        'punti',    COALESCE(v_h.points_reward, 0),
        'motivo',   'completato',
        'scheda',   v_scheda,
        'regole',   v_regole
      );
      CONTINUE;
    END IF;

    -- ── 4. Scaduto per calendario ─────────────────────────────────
    IF COALESCE(v_h.goal, 0) > 0 THEN
      -- ⚠️ Per count_window `goal` sono FINESTRE: l'ultimo giorno è
      -- `inizio + goal × M − 1`, non `inizio + goal − 1`. Con la
      -- formula dei giorni una stecca di 4 finestre da 7 giorni
      -- scadrebbe al quarto giorno.
      v_fine := v_inizio
                + CASE WHEN v_h.frequency = 'count_window'
                       THEN v_h.goal * v_m ELSE v_h.goal END
                - 1;

      -- Anche **l'ultimo giorno**, ma solo quando di quel giorno non
      -- resta niente in sospeso.
      IF v_oggi > v_fine
         OR (v_oggi = v_fine AND hb_giorno_risolto(v_h.id, v_oggi)) THEN
        v_giorni  := hb_giorni_fatti(v_h.id, v_fine);
        v_mancati := v_h.goal - v_giorni;
        v_coperto := COALESCE(v_h.max_failures, 0) > 0
                     AND v_mancati <= v_h.max_failures;

        SELECT count(*) INTO v_gia
          FROM hb_archived_stacks a
         WHERE a.habit_id = v_h.id AND a.started_at = v_h.started_at;

        SELECT to_jsonb(r) INTO v_regola
          FROM cm_notification_rules r
         WHERE r.app = 'habits' AND r.entity_id = v_h.id
         LIMIT 1;

        IF v_gia = 0 THEN
          INSERT INTO hb_archived_stacks (
            habit_id, habit_name, category_id, started_at, ended_at,
            final_streak, total_days, total_completions, total_failures,
            points_earned, reason
          ) VALUES (
            v_h.id, v_h.name, v_h.category_id, v_h.started_at, v_oggi,
            v_giorni, v_h.goal,
            (SELECT count(*) FROM hb_completions c
              WHERE c.habit_id = v_h.id AND c.status = 'completed'
                AND c.completed_at::date >= v_inizio),
            (SELECT count(*) FROM hb_completions c
              WHERE c.habit_id = v_h.id AND c.status IN ('failed', 'missed')
                AND c.completed_at::date >= v_inizio),
            CASE WHEN v_coperto THEN COALESCE(v_h.points_reward, 0)
                 ELSE -COALESCE(v_h.points_penalty, 0) END,
            CASE WHEN v_coperto THEN 'completato_con_jolly' ELSE 'scadenza_calendario' END
          );
        END IF;

        DELETE FROM cm_notification_rules WHERE app = 'habits' AND entity_id = v_h.id;
        DELETE FROM hb_habits WHERE id = v_h.id;

        IF v_gia = 0 THEN
          IF v_coperto THEN
            v_completati := v_completati || jsonb_build_object(
              'habit_id', v_h.id,
              'nome',     v_h.name,
              'streak',   v_giorni,
              'punti',    COALESCE(v_h.points_reward, 0),
              'motivo',   'completato_con_jolly',
              'scheda',   v_scheda,
              'regola',   v_regola,
              'regole',   v_regole
            );
          ELSE
            v_gameover := v_gameover || jsonb_build_object(
              'habit_id',  v_h.id,
              'nome',      v_h.name,
              'streak',    v_giorni,
              'giorni',    v_h.goal,
              'mancati',   v_mancati,
              'motivo',    'scadenza_calendario',
              'archiviato', true,
              'scheda',    v_scheda,
              'regola',    v_regola,
              'regole',    v_regole
            );
          END IF;
        END IF;
        CONTINUE;
      END IF;
    END IF;

    -- ── 5. Jolly esauriti: si segnala, non si chiude ──────────────
    IF COALESCE(v_h.max_failures, 0) > 0 AND v_fallimenti >= v_h.max_failures THEN
      v_gameover := v_gameover || jsonb_build_object(
        'habit_id',  v_h.id,
        'nome',      v_h.name,
        'streak',    v_streak,
        'giorni',    (v_oggi - v_inizio),
        'mancati',   v_fallimenti,
        'motivo',    'jolly_esauriti',
        'archiviato', false,
        'scheda',    v_scheda,
        'regole',    v_regole
      );
    END IF;
  END LOOP;

  RETURN jsonb_build_object(
    'ok',         true,
    'completati', v_completati,
    'game_over',  v_gameover
  );
END;
$$;

COMMENT ON FUNCTION public.hb_reconcile(date) IS
  'Il giro di riconciliazione: periodi mancati, jolly, stack completati e scaduti. Conosce anche count_window, dove il periodo e la finestra e ogni pallino rosso costa un jolly.';

GRANT EXECUTE ON FUNCTION public.hb_reconcile(date) TO authenticated;
