-- =====================================================================
-- Abituati — le RPC imparano «N volte in M giorni» (count_window)
--
-- La quarta frequenza è nata solo nel web (v9.5.4-9.5.7), e le RPC `hb_*`
-- non la conoscevano: cadeva nell'`ELSE false` di ogni ramo, quindi in
-- nativo un'abitudine `count_window` **non compariva affatto** — nessun
-- giorno dovuto, nessuno streak, nessuna mancata. Senza questo file la
-- schermata Kotlin che la disegna non avrebbe niente da disegnare.
--
-- ⚠️ **Queste RPC oggi le chiama solo il nativo**: `habit-tracker.html`
-- usa ancora le sue copie in JavaScript (`checkMissedDays`,
-- `computeHabitStreak`, `countCompletedDaysInPeriod`, `isDayResolved`,
-- `checkExpiredStacks`). Il SQL qui sotto è ricalcato da quelle riga per
-- riga: se cambia una delle due, va cambiata anche l'altra.
--
-- Le tre grandezze, dalle colonne aggiunte in
-- `20260915120000_hb_habits_count_window.sql` e
-- `20260916100000_hb_habits_max_per_day.sql`:
--
--   N = times_target   quante volte dentro una finestra
--   M = window_days    quanti giorni dura una finestra
--   P = max_per_day    quante spunte al massimo nello stesso giorno
--
-- ⚠️ **Le finestre si contano dal giorno di inizio** e non dal
-- calendario: [inizio, inizio+M-1], [inizio+M, inizio+2M-1], … — la
-- stessa aritmetica di `cwWindowStartByIndex()`. E `goal` qui sono
-- **finestre**, non giorni: è la ragione per cui `hb_giorni_fatti` deve
-- avere un ramo suo, o un obiettivo di 3 finestre da 7 giorni si
-- chiuderebbe al terzo giorno.
--
-- ⚠️ **Una finestra mancata costa UN JOLLY PER PALLINO ROSSO**, non uno
-- per finestra: chiusa a 2 su 6 sono quattro mancate e quattro jolly. Le
-- righe `missed` portano quindi il progressivo nella chiave
-- (`w2026-09-16#3`), che serve a riconoscere quelle già scritte senza
-- ricontarle a ogni giro. `hb_fallimenti` le conta una per una perché
-- count_window non è `daily_multiple`, quindi cade nel ramo che conta le
-- righe invece dei giorni distinti — ed è quel che qui serve.
--
-- ⚠️ `hb_periodo_key` NON si tocca: per count_window il ＋ non passa da
-- `hb_set_completion` ma scrive la riga da sé con la chiave
-- `<giorno>#<n>`, perché con più spunte nello stesso giorno un upsert
-- sulla chiave `= data` aggiornerebbe la prima invece di aggiungerne
-- una. È la stessa ragione per cui nel web `cwAggiungi` non passa da
-- `setDayState`.
-- =====================================================================

-- ── Quante spunte dentro una finestra ────────────────────────────────
--
-- Il gemello di `cwWindowDoneCount()`. Contano le sole `completed`: una
-- `missed` scritta dalla riconciliazione è il contrario di fatto.
CREATE OR REPLACE FUNCTION public.hb_cw_fatte(
  p_habit_id uuid,
  p_inizio   date,
  p_giorni   integer
)
RETURNS integer
-- Sola lettura: SECURITY INVOKER come `hb_giorno_fatto`, così la RLS su
-- `hb_completions` resta in mezzo.
LANGUAGE sql STABLE SECURITY INVOKER SET search_path = public
AS $$
  SELECT count(*)::integer
    FROM hb_completions c
   WHERE c.habit_id = p_habit_id
     AND c.status = 'completed'
     AND c.completed_at::date >= p_inizio
     AND c.completed_at::date <= p_inizio + GREATEST(1, p_giorni) - 1
$$;

COMMENT ON FUNCTION public.hb_cw_fatte(uuid, date, integer) IS
  'Quante spunte completate dentro la finestra che comincia a p_inizio e dura p_giorni. Gemella di cwWindowDoneCount() in habit-tracker.html.';

GRANT EXECUTE ON FUNCTION public.hb_cw_fatte(uuid, date, integer) TO authenticated;


-- ── Lo streak, a finestre ────────────────────────────────────────────
--
-- Identica a `20260815120000_hb_regole_rpc.sql` più il ramo
-- count_window in testa: plpgsql non sa sostituire un pezzo di funzione,
-- quindi il resto è riscritto tale e quale.
CREATE OR REPLACE FUNCTION public.hb_streak(p_habit_id uuid, p_oggi date DEFAULT CURRENT_DATE)
RETURNS integer
LANGUAGE plpgsql STABLE SECURITY INVOKER SET search_path = public
AS $$
DECLARE
  v_h        hb_habits%ROWTYPE;
  v_inizio   date;
  v_oggi     date := COALESCE(p_oggi, CURRENT_DATE);
  v_streak   integer := 0;
  v_giorno   date;
  v_giorni   text[];
  v_lunedi   date;
  v_primo    date;
  v_completa boolean;
  v_dow      text;
  v_data     date;
  v_m        integer;
  v_n        integer;
  v_k        integer;
  v_kcur     integer;
BEGIN
  SELECT * INTO v_h FROM hb_habits WHERE id = p_habit_id;
  IF NOT FOUND THEN RETURN 0; END IF;

  v_inizio := COALESCE(v_h.started_at::date, v_h.created_at::date, v_oggi);
  v_giorni := hb_lista(to_jsonb(v_h) -> 'weekdays');

  -- ── «N volte in M giorni»: si contano le FINESTRE ────────────────
  --
  -- Gemella di `cwStreak()`. ⚠️ La finestra **in corso** non ancora
  -- piena non spezza la serie, esattamente come «oggi» per le
  -- giornaliere: è ancora in tempo.
  IF v_h.frequency = 'count_window' THEN
    v_m := GREATEST(1, COALESCE(v_h.window_days, 1));
    v_n := GREATEST(1, COALESCE(v_h.times_target, 1));
    v_kcur := (v_oggi - v_inizio) / v_m;
    IF v_oggi < v_inizio THEN RETURN 0; END IF;

    FOR v_k IN REVERSE v_kcur .. 0 LOOP
      IF hb_cw_fatte(p_habit_id, v_inizio + v_k * v_m, v_m) >= v_n THEN
        v_streak := v_streak + 1;
      ELSIF v_k < v_kcur THEN
        EXIT;
      END IF;
    END LOOP;

    RETURN v_streak;
  END IF;

  -- ── Settimanale: si contano le settimane intere ──────────────────
  IF v_h.frequency = 'weekly' AND array_length(v_giorni, 1) > 0 THEN
    v_primo  := v_inizio - (EXTRACT(isodow FROM v_inizio)::int - 1);
    v_lunedi := v_oggi - (EXTRACT(isodow FROM v_oggi)::int - 1);

    WHILE v_lunedi >= v_primo LOOP
      v_completa := true;
      FOREACH v_dow IN ARRAY v_giorni LOOP
        v_data := v_lunedi + CASE WHEN v_dow::int = 0 THEN 6 ELSE v_dow::int - 1 END;
        CONTINUE WHEN v_data < v_inizio;
        IF v_data > v_oggi OR NOT hb_giorno_fatto(p_habit_id, v_data) THEN
          v_completa := false;
          EXIT;
        END IF;
      END LOOP;

      EXIT WHEN NOT v_completa;
      v_streak := v_streak + 1;
      v_lunedi := v_lunedi - 7;
    END LOOP;

    RETURN v_streak;
  END IF;

  -- ── Giornaliera, anche a più orari ───────────────────────────────
  v_giorno := v_oggi;
  WHILE v_giorno >= v_inizio LOOP
    EXIT WHEN NOT hb_giorno_fatto(p_habit_id, v_giorno);
    v_streak := v_streak + 1;
    v_giorno := v_giorno - 1;
  END LOOP;

  RETURN v_streak;
END;
$$;

GRANT EXECUTE ON FUNCTION public.hb_streak(uuid, date) TO authenticated;


-- ── I «giorni fatti», che per count_window sono FINESTRE ─────────────
--
-- ⚠️ Il nome resta quello e la grandezza cambia, ed è voluto: chi la
-- chiama la confronta con `goal`, e per count_window `goal` sono
-- finestre. Rinominarla vorrebbe dire toccare `hb_reconcile` in quattro
-- punti per non cambiare nessun numero.
CREATE OR REPLACE FUNCTION public.hb_giorni_fatti(p_habit_id uuid, p_fino date)
RETURNS integer
LANGUAGE plpgsql STABLE SECURITY INVOKER SET search_path = public
AS $$
DECLARE
  v_h      hb_habits%ROWTYPE;
  v_giorno date;
  v_conta  integer := 0;
  v_inizio date;
  v_m      integer;
  v_n      integer;
  v_k      integer := 0;
  v_ws     date;
BEGIN
  SELECT * INTO v_h FROM hb_habits WHERE id = p_habit_id;
  IF NOT FOUND THEN RETURN 0; END IF;

  v_inizio := COALESCE(v_h.started_at::date, v_h.created_at::date, CURRENT_DATE);

  -- Gemella di `cwSatisfiedWindows()`: le finestre soddisfatte che
  -- cominciano entro `p_fino`, al più `goal`.
  IF v_h.frequency = 'count_window' THEN
    v_m := GREATEST(1, COALESCE(v_h.window_days, 1));
    v_n := GREATEST(1, COALESCE(v_h.times_target, 1));
    LOOP
      EXIT WHEN COALESCE(v_h.goal, 0) > 0 AND v_k >= v_h.goal;
      v_ws := v_inizio + v_k * v_m;
      EXIT WHEN v_ws > p_fino;
      IF hb_cw_fatte(p_habit_id, v_ws, v_m) >= v_n THEN
        v_conta := v_conta + 1;
      END IF;
      v_k := v_k + 1;
      EXIT WHEN v_k > 100000;
    END LOOP;
    RETURN v_conta;
  END IF;

  v_giorno := v_inizio;
  WHILE v_giorno <= p_fino LOOP
    IF hb_giorno_fatto(p_habit_id, v_giorno) THEN
      v_conta := v_conta + 1;
    END IF;
    v_giorno := v_giorno + 1;
  END LOOP;

  RETURN v_conta;
END;
$$;

GRANT EXECUTE ON FUNCTION public.hb_giorni_fatti(uuid, date) TO authenticated;


-- ── Di quel giorno resta qualcosa in sospeso? ────────────────────────
--
-- Identica a `20260827100000_hb_chiusura_ultimo_giorno.sql` più il ramo
-- count_window. ⚠️ Lì la domanda è sul giorno, qui sulla **finestra** che
-- lo contiene: è risolta se è già piena, oppure se è finita — dopo non
-- c'è più niente da segnarci.
CREATE OR REPLACE FUNCTION public.hb_giorno_risolto(p_habit_id uuid, p_giorno date)
RETURNS boolean
LANGUAGE plpgsql STABLE SECURITY INVOKER SET search_path = public
AS $$
DECLARE
  v_h      hb_habits%ROWTYPE;
  v_orari  text[];
  v_giorni text[];
  v_orario text;
  v_key    text;
  v_inizio date;
  v_m      integer;
  v_n      integer;
  v_ws     date;
BEGIN
  SELECT * INTO v_h FROM hb_habits WHERE id = p_habit_id;
  IF NOT FOUND THEN RETURN false; END IF;

  v_orari  := hb_lista(to_jsonb(v_h) -> 'daily_times');
  v_giorni := hb_lista(to_jsonb(v_h) -> 'weekdays');

  IF v_h.frequency = 'count_window' THEN
    v_inizio := COALESCE(v_h.started_at::date, v_h.created_at::date);
    IF v_inizio IS NULL OR p_giorno < v_inizio THEN RETURN true; END IF;
    v_m  := GREATEST(1, COALESCE(v_h.window_days, 1));
    v_n  := GREATEST(1, COALESCE(v_h.times_target, 1));
    v_ws := v_inizio + ((p_giorno - v_inizio) / v_m) * v_m;
    RETURN hb_cw_fatte(p_habit_id, v_ws, v_m) >= v_n
           OR (v_ws + v_m - 1) < p_giorno;
  END IF;

  IF v_h.frequency = 'weekly' THEN
    -- I giorni sono numerati alla JavaScript: 0 = domenica.
    IF NOT ((EXTRACT(dow FROM p_giorno)::int)::text = ANY (v_giorni)) THEN
      RETURN true;
    END IF;
  ELSIF v_h.frequency NOT IN ('daily', 'daily_multiple') THEN
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

GRANT EXECUTE ON FUNCTION public.hb_giorno_risolto(uuid, date) TO authenticated;


-- ── Quanto costa riprendere ──────────────────────────────────────────
--
-- Identica a `20260825100000_hb_giorni_da_recuperare.sql` più il ramo
-- count_window: lì si contano i giorni dovuti senza spunta, qui i
-- **pallini rossi** delle finestre che si chiuderebbero fra la data
-- scelta e ieri. Senza questo ramo la finestra della ripresa direbbe
-- «non costa niente» su un'abitudine che invece i jolly li consuma.
CREATE OR REPLACE FUNCTION public.hb_giorni_da_recuperare(
  p_habit_id uuid,
  p_da       date,
  p_oggi     date DEFAULT CURRENT_DATE
)
RETURNS integer
LANGUAGE plpgsql STABLE SECURITY INVOKER SET search_path = public
AS $$
DECLARE
  v_h      hb_habits%ROWTYPE;
  v_oggi   date := COALESCE(p_oggi, CURRENT_DATE);
  v_giorno date;
  v_giorni text[];
  v_dovuto boolean;
  v_conta  integer := 0;
  v_m      integer;
  v_n      integer;
  v_k      integer := 0;
  v_ws     date;
BEGIN
  IF p_da IS NULL THEN RETURN 0; END IF;

  SELECT * INTO v_h FROM hb_habits WHERE id = p_habit_id;
  IF NOT FOUND THEN RETURN 0; END IF;

  v_giorni := hb_lista(to_jsonb(v_h) -> 'weekdays');

  -- Riprendendo da `p_da` le finestre ripartono da lì: si contano i
  -- pallini che resterebbero rossi in quelle già chiuse.
  IF v_h.frequency = 'count_window' THEN
    v_m := GREATEST(1, COALESCE(v_h.window_days, 1));
    v_n := GREATEST(1, COALESCE(v_h.times_target, 1));
    LOOP
      EXIT WHEN COALESCE(v_h.goal, 0) > 0 AND v_k >= v_h.goal;
      v_ws := p_da + v_k * v_m;
      EXIT WHEN v_ws + v_m - 1 >= v_oggi;
      v_conta := v_conta + GREATEST(0, v_n - hb_cw_fatte(p_habit_id, v_ws, v_m));
      v_k := v_k + 1;
      EXIT WHEN v_k > 100000;
    END LOOP;
    RETURN v_conta;
  END IF;

  v_giorno := p_da;

  -- Da `p_da` a **ieri**: oggi è ancora in tempo.
  WHILE v_giorno < v_oggi LOOP
    v_dovuto := CASE
      WHEN v_h.frequency IN ('daily', 'daily_multiple') THEN true
      -- I giorni sono numerati alla JavaScript: 0 = domenica.
      WHEN v_h.frequency = 'weekly' THEN
        (EXTRACT(dow FROM v_giorno)::int)::text = ANY (v_giorni)
      ELSE false
    END;

    IF v_dovuto AND NOT hb_giorno_fatto(p_habit_id, v_giorno) THEN
      v_conta := v_conta + 1;
    END IF;

    v_giorno := v_giorno + 1;
  END LOOP;

  RETURN v_conta;
END;
$$;

GRANT EXECUTE ON FUNCTION public.hb_giorni_da_recuperare(uuid, date, date) TO authenticated;


-- ── Il giro di riconciliazione ───────────────────────────────────────
--
-- Identica a `20260827100000_hb_chiusura_ultimo_giorno.sql` a meno di
-- due punti — il passo 1 (le mancate) e la scadenza del passo 4 — ma
-- riscritta per intero, perché plpgsql non sa sostituire un pezzo di
-- funzione.
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
  'Il giro di riconciliazione: periodi mancati, jolly, stack completati e scaduti. Conosce anche count_window, dove il periodo e la finestra e ogni pallino rosso costa un jolly.';

GRANT EXECUTE ON FUNCTION public.hb_reconcile(date) TO authenticated;
