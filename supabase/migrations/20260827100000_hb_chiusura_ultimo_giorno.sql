-- =====================================================================
-- Abituati — uno stack si chiude anche l'ultimo giorno
--
-- Il difetto, visto su una stecca **giornaliera a più orari**: segnati
-- tutti gli appuntamenti dell'ultimo giorno — chi fatto, chi saltato
-- col jolly — non succedeva niente. La stecca restava lì, e si chiudeva
-- solo riaprendo l'app **il giorno dopo**.
--
-- Le due strade per chiudere uno stack si perdevano lo stesso giorno:
--
--   * l'obiettivo raggiunto (`hb_giorni_fatti >= goal`) conta i soli
--     giorni **fatti per intero**, e un giorno in cui un appuntamento è
--     stato saltato non ci entra — per quanti giorni si segnino, il
--     conto non arriva mai al traguardo;
--   * la scadenza del calendario, che i giorni saltati li copre coi
--     jolly (`completato_con_jolly`), scattava solo con `v_oggi >
--     v_fine`, cioè **dal giorno dopo l'ultimo**.
--
-- In mezzo restava un giorno intero — proprio quello in cui l'utente
-- finisce la stecca e guarda l'app aspettandosi la festa.
--
-- Qui la scadenza impara a scattare **anche l'ultimo giorno**, ma solo
-- quando di quel giorno non resta niente in sospeso: sull'ultimo giorno
-- alle otto del mattino chiudere sarebbe peggio del difetto — la stecca
-- verrebbe archiviata come persa mentre c'è ancora tutto il tempo per
-- finirla. Da qui `hb_giorno_risolto()`.
--
-- ⚠️ La stessa regola vive in `habit-tracker.html` (`checkExpiredStacks`
-- + `isDayResolved`), che le sue funzioni ce le ha ancora in
-- JavaScript: se cambia una, va cambiata l'altra.
-- =====================================================================

-- ── Di quel giorno resta qualcosa in sospeso? ────────────────────────
--
-- «Risolto» non vuol dire «fatto»: vuol dire che ogni periodo di quel
-- giorno ha la sua riga, comunque sia andata — `completed`, `failed`,
-- `skipped` o `missed`. È la domanda che serve per sapere se si può già
-- tirare le somme, e non ha niente a che vedere con `hb_giorno_fatto`,
-- che invece chiede che sia andata bene.
--
-- Un giorno che non era dovuto (il mercoledì di una settimanale che
-- cade solo di lunedì) non è in sospeso: non c'era niente da segnare.
CREATE OR REPLACE FUNCTION public.hb_giorno_risolto(p_habit_id uuid, p_giorno date)
RETURNS boolean
-- SECURITY INVOKER come `hb_giorno_fatto`: legge e basta, quindi la RLS
-- su `hb_completions` deve restare in mezzo. Chiamata da `hb_reconcile`,
-- che è DEFINER, gira comunque come proprietario e vede tutto.
LANGUAGE plpgsql STABLE SECURITY INVOKER SET search_path = public
AS $$
DECLARE
  v_h      hb_habits%ROWTYPE;
  v_orari  text[];
  v_giorni text[];
  v_orario text;
  v_key    text;
BEGIN
  SELECT * INTO v_h FROM hb_habits WHERE id = p_habit_id;
  IF NOT FOUND THEN RETURN false; END IF;

  v_orari  := hb_lista(to_jsonb(v_h) -> 'daily_times');
  v_giorni := hb_lista(to_jsonb(v_h) -> 'weekdays');

  IF v_h.frequency = 'weekly' THEN
    -- I giorni sono numerati alla JavaScript: 0 = domenica.
    IF NOT ((EXTRACT(dow FROM p_giorno)::int)::text = ANY (v_giorni)) THEN
      RETURN true;
    END IF;
  ELSIF v_h.frequency NOT IN ('daily', 'daily_multiple') THEN
    -- Una frequenza che non conosciamo non chiede niente a nessun
    -- giorno: è la stessa scelta dell'`ELSE false` di `hb_reconcile`.
    RETURN true;
  END IF;

  IF v_h.frequency = 'daily_multiple' AND array_length(v_orari, 1) > 0 THEN
    FOREACH v_orario IN ARRAY v_orari LOOP
      v_key := hb_periodo_key(v_h.frequency, p_giorno, v_orario);
      IF NOT EXISTS (
        SELECT 1 FROM hb_completions c
         WHERE c.habit_id = p_habit_id
           AND (c.period_key = v_key
                OR (c.period_key IS NULL
                    AND c.completed_at::date = p_giorno
                    AND to_char(c.completed_at, 'HH24:MI') = v_orario))
      ) THEN
        RETURN false;
      END IF;
    END LOOP;
    RETURN true;
  END IF;

  RETURN EXISTS (
    SELECT 1 FROM hb_completions c
     WHERE c.habit_id = p_habit_id AND c.completed_at::date = p_giorno
  );
END;
$$;

COMMENT ON FUNCTION public.hb_giorno_risolto(uuid, date) IS
  'Di quel giorno non resta niente in sospeso: ogni periodo dovuto ha la sua riga, comunque sia andata. Un giorno non dovuto è risolto per definizione.';

GRANT EXECUTE ON FUNCTION public.hb_giorno_risolto(uuid, date) TO authenticated;


-- ── Il giro di riconciliazione, con la scadenza corretta ─────────────
--
-- Identica a `20260815120000_hb_regole_rpc.sql` a meno del passo 4: la
-- condizione di scadenza. Il resto è riscritto tale e quale perché
-- plpgsql non sa sostituire un pezzo di funzione.
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
  v_gia         integer;
  v_completati  jsonb := '[]'::jsonb;
  v_gameover    jsonb := '[]'::jsonb;
  v_scheda      jsonb;
BEGIN
  IF NOT public.is_garsal() THEN
    RETURN jsonb_build_object('ok', false, 'error', 'non autorizzato');
  END IF;

  FOR v_h IN SELECT * FROM hb_habits WHERE status = 'active' LOOP
    v_inizio := v_h.started_at::date;
    CONTINUE WHEN v_inizio IS NULL OR v_inizio > v_oggi;

    v_orari       := hb_lista(to_jsonb(v_h) -> 'daily_times');
    v_giorni_sett := hb_lista(to_jsonb(v_h) -> 'weekdays');

    -- ── 1. I periodi passati senza riga diventano `missed` ────────
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

    -- ── 2. I jolly si riallineano al conteggio vero ───────────────
    v_fallimenti := hb_fallimenti(v_h.id);
    IF COALESCE(v_h.current_failures, 0) IS DISTINCT FROM v_fallimenti THEN
      UPDATE hb_habits SET current_failures = v_fallimenti WHERE id = v_h.id;
      v_h.current_failures := v_fallimenti;
    END IF;

    v_streak := hb_streak(v_h.id, v_oggi);
    v_giorni := hb_giorni_fatti(v_h.id, v_oggi);

    v_scheda := to_jsonb(v_h);

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
        'scheda',   v_scheda
      );
      CONTINUE;
    END IF;

    -- ── 4. Scaduto per calendario ─────────────────────────────────
    IF COALESCE(v_h.goal, 0) > 0 THEN
      v_fine := v_inizio + v_h.goal - 1;

      -- ⚠️ Anche **l'ultimo giorno**, non solo dal giorno dopo — ma
      -- solo quando di quel giorno non resta niente in sospeso.
      -- Chiudere l'ultimo giorno a stecca ancora da segnare vorrebbe
      -- dire archiviarla come persa mentre c'era ancora tempo.
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
              'regola',   v_regola
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
              'regola',    v_regola
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
        'scheda',    v_scheda
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
  'Il giro di riconciliazione: giorni mancati, jolly, stack completati e scaduti. La scadenza per calendario scatta gia l''ultimo giorno, quando di quel giorno non resta niente in sospeso.';

GRANT EXECUTE ON FUNCTION public.hb_reconcile(date) TO authenticated;
