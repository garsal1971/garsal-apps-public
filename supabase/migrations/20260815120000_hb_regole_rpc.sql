-- =====================================================================
-- Abituati — le regole in RPC, come per i task
--
-- Fino a oggi streak, jolly, chiusura degli stack e giorni mancati
-- vivevano **solo** in `habit-tracker.html`. Portare l'app in nativo
-- avrebbe voluto dire riscrivere quelle regole in Kotlin: due copie
-- della stessa formula di punteggio, che il giorno che una cambia
-- divergono in silenzio — e qui in ballo ci sono punti e archivi.
--
-- Quindi la logica scende nel database, e web e nativo la chiamano.
-- È la stessa scelta di `task_complete` / `task_skip` / `task_fail`.
--
-- ⚠️ Il principio che regge tutto — ed è quello che il JS già seguiva
-- in `checkMissedDays` — è che **la fonte di verità sono i
-- completamenti**: `hb_habits.current_failures` non si incrementa e non
-- si decrementa, si **ricalcola** da `hb_completions` ogni volta. Così
-- due client che scrivono lo stesso giorno non possono contare due
-- volte lo stesso jolly.
--
-- Cosa NON fanno queste funzioni, di proposito: chiudere uno stack che
-- ha esaurito i jolly. Nel web quella è una cerimonia — il modale
-- «game over» con Ricomincia / Interrompi / Ripristina — e le scritture
-- avvengono dopo che l'utente ha scelto. `hb_reconcile()` si limita a
-- segnalarlo, esattamente come `autoCloseFailedStack()`, che accoda il
-- modale e non tocca il database.
-- =====================================================================

-- ── Helper: una colonna lista, qualunque tipo abbia ──────────────────
--
-- `weekdays` e `daily_times` non stanno in nessuna migration: sono nate
-- a mano e possono essere `text[]`, `integer[]` o `jsonb`. Passando dal
-- jsonb della riga si leggono uguale in tutti e tre i casi, e una
-- colonna vuota o nulla dà una lista vuota invece di un errore.
CREATE OR REPLACE FUNCTION public.hb_lista(p_valore jsonb)
RETURNS text[]
LANGUAGE sql IMMUTABLE
AS $$
  SELECT CASE
    WHEN p_valore IS NULL OR jsonb_typeof(p_valore) <> 'array' THEN '{}'::text[]
    ELSE ARRAY(SELECT jsonb_array_elements_text(p_valore))
  END
$$;

-- ── La chiave del periodo, identica a `buildPeriodKey()` ─────────────
CREATE OR REPLACE FUNCTION public.hb_periodo_key(
  p_frequenza text,
  p_giorno    date,
  p_orario    text DEFAULT NULL
)
RETURNS text
LANGUAGE sql IMMUTABLE
AS $$
  SELECT CASE
    WHEN p_frequenza = 'daily_multiple' THEN p_giorno::text || '-' || COALESCE(p_orario, '')
    -- Settimanale: lunedì della settimana + numero ISO del giorno
    -- (1 = lunedì … 7 = domenica), come `${monday}-${n}` nel web.
    WHEN p_frequenza = 'weekly' THEN
      (p_giorno - (EXTRACT(isodow FROM p_giorno)::int - 1))::text
        || '-' || EXTRACT(isodow FROM p_giorno)::int::text
    ELSE p_giorno::text
  END
$$;

-- ── Un giorno «conta come fatto»? ────────────────────────────────────
--
-- Per le abitudini a più orari serve che **tutti** gli slot del giorno
-- siano completati; per le altre basta una spunta. Contano solo le
-- righe `completed`: `failed` e `missed` sono il contrario di fatto.
CREATE OR REPLACE FUNCTION public.hb_giorno_fatto(p_habit_id uuid, p_giorno date)
RETURNS boolean
-- SECURITY INVOKER di proposito, come `ob_metric_current`: legge e basta,
-- quindi la RLS su `hb_completions` deve restare in mezzo. Girando come
-- proprietario, chiunque avesse la anon key potrebbe chiedere se un certo
-- giorno è stato spuntato. Chiamata da `hb_reconcile`, che è DEFINER, gira
-- comunque come proprietario e vede tutto.
LANGUAGE plpgsql STABLE SECURITY INVOKER SET search_path = public
AS $$
DECLARE
  v_h      hb_habits%ROWTYPE;
  v_slot   integer;
  v_fatti  integer;
BEGIN
  SELECT * INTO v_h FROM hb_habits WHERE id = p_habit_id;
  IF NOT FOUND THEN RETURN false; END IF;

  v_slot := COALESCE(array_length(hb_lista(to_jsonb(v_h) -> 'daily_times'), 1), 0);

  SELECT count(*) INTO v_fatti
    FROM hb_completions c
   WHERE c.habit_id = p_habit_id
     AND c.status = 'completed'
     AND c.completed_at::date = p_giorno;

  IF v_h.frequency = 'daily_multiple' AND v_slot > 0 THEN
    RETURN v_fatti >= v_slot;
  END IF;
  RETURN v_fatti > 0;
END;
$$;

-- ── Lo streak ────────────────────────────────────────────────────────
--
-- Ricalcato da `calculateDailyStreak` / `calculateDailyMultipleStreak` /
-- `calculateWeeklyStreak`, compreso il fatto che **si parte da oggi**:
-- finché oggi non è spuntato lo streak è zero, e non «quello di ieri».
-- È severo, ma è la regola con cui gli stack sono stati costruiti.
--
-- ⚠️ Una differenza voluta rispetto al web: qui contano **solo** le
-- righe `completed`. Nel web `updateStreak()` filtra così, ma
-- `computeHabitStreak()` — quella che disegna la dashboard — passa
-- tutte le righe, quindi un giorno segnato `failed` gli risulta fatto e
-- lo streak a schermo è più lungo del vero. Le due strade erano già in
-- disaccordo fra loro: questa è quella che regge il punteggio.
CREATE OR REPLACE FUNCTION public.hb_streak(p_habit_id uuid, p_oggi date DEFAULT CURRENT_DATE)
RETURNS integer
-- Sola lettura: SECURITY INVOKER, vedi `hb_giorno_fatto`.
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
BEGIN
  SELECT * INTO v_h FROM hb_habits WHERE id = p_habit_id;
  IF NOT FOUND THEN RETURN 0; END IF;

  v_inizio := COALESCE(v_h.started_at::date, v_h.created_at::date, v_oggi);
  v_giorni := hb_lista(to_jsonb(v_h) -> 'weekdays');

  -- ── Settimanale: si contano le settimane intere ──────────────────
  IF v_h.frequency = 'weekly' AND array_length(v_giorni, 1) > 0 THEN
    -- Lunedì della settimana in cui cade la data di inizio, e lunedì
    -- di questa settimana: si va a ritroso da qui fino a lì.
    v_primo  := v_inizio - (EXTRACT(isodow FROM v_inizio)::int - 1);
    v_lunedi := v_oggi - (EXTRACT(isodow FROM v_oggi)::int - 1);

    WHILE v_lunedi >= v_primo LOOP
      v_completa := true;
      FOREACH v_dow IN ARRAY v_giorni LOOP
        -- I giorni arrivano numerati alla JavaScript (0 = domenica);
        -- il lunedì della settimana più l'offset dà la data.
        v_data := v_lunedi + CASE WHEN v_dow::int = 0 THEN 6 ELSE v_dow::int - 1 END;

        -- Prima dell'inizio: si considera fatto, come nel web.
        CONTINUE WHEN v_data < v_inizio;

        -- Un giorno ancora da venire non rende la settimana completa.
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

-- ── I giorni fatti nel periodo, come `countCompletedDaysInPeriod` ────
CREATE OR REPLACE FUNCTION public.hb_giorni_fatti(p_habit_id uuid, p_fino date)
RETURNS integer
-- Sola lettura: SECURITY INVOKER, vedi `hb_giorno_fatto`.
LANGUAGE plpgsql STABLE SECURITY INVOKER SET search_path = public
AS $$
DECLARE
  v_h      hb_habits%ROWTYPE;
  v_giorno date;
  v_conta  integer := 0;
BEGIN
  SELECT * INTO v_h FROM hb_habits WHERE id = p_habit_id;
  IF NOT FOUND THEN RETURN 0; END IF;

  v_giorno := COALESCE(v_h.started_at::date, v_h.created_at::date, CURRENT_DATE);
  WHILE v_giorno <= p_fino LOOP
    IF hb_giorno_fatto(p_habit_id, v_giorno) THEN
      v_conta := v_conta + 1;
    END IF;
    v_giorno := v_giorno + 1;
  END LOOP;

  RETURN v_conta;
END;
$$;

-- ── I jolly consumati ────────────────────────────────────────────────
--
-- Si **contano**, non si tengono: per le abitudini a più orari vale un
-- jolly per giorno mancato, non per sessione (tre slot saltati lo
-- stesso giorno costano un jolly solo), che è la regola scritta a mano
-- in tre punti diversi di `habit-tracker.html`.
CREATE OR REPLACE FUNCTION public.hb_fallimenti(p_habit_id uuid)
RETURNS integer
-- Sola lettura: SECURITY INVOKER, vedi `hb_giorno_fatto`.
LANGUAGE plpgsql STABLE SECURITY INVOKER SET search_path = public
AS $$
DECLARE
  v_h     hb_habits%ROWTYPE;
  v_slot  integer;
  v_conta integer;
BEGIN
  SELECT * INTO v_h FROM hb_habits WHERE id = p_habit_id;
  IF NOT FOUND THEN RETURN 0; END IF;

  v_slot := COALESCE(array_length(hb_lista(to_jsonb(v_h) -> 'daily_times'), 1), 0);

  IF v_h.frequency = 'daily_multiple' AND v_slot > 0 THEN
    SELECT count(DISTINCT c.completed_at::date) INTO v_conta
      FROM hb_completions c
     WHERE c.habit_id = p_habit_id
       AND c.status IN ('failed', 'missed')
       AND c.completed_at::date >= COALESCE(v_h.started_at::date, '-infinity'::date);
  ELSE
    SELECT count(*) INTO v_conta
      FROM hb_completions c
     WHERE c.habit_id = p_habit_id
       AND c.status IN ('failed', 'missed')
       AND c.completed_at::date >= COALESCE(v_h.started_at::date, '-infinity'::date);
  END IF;

  RETURN COALESCE(v_conta, 0);
END;
$$;

-- ── L'unica strada per segnare un giorno ─────────────────────────────
--
-- `p_stato` vale `completed`, `failed`, `skipped` o `none` (toglie la
-- riga). `p_orario` serve solo alle abitudini a più orari.
--
-- Dopo la scrittura ricalcola i jolly dai completamenti e restituisce
-- quello che il client deve sapere per disegnare: streak, jolly,
-- e i due segnali che aprono una cerimonia — stack da chiudere e jolly
-- esauriti. La cerimonia resta al client, qui non si archivia niente.
CREATE OR REPLACE FUNCTION public.hb_set_completion(
  p_habit_id uuid,
  p_giorno   date,
  p_stato    text,
  p_orario   text DEFAULT NULL,
  p_oggi     date DEFAULT CURRENT_DATE
)
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
DECLARE
  v_h           hb_habits%ROWTYPE;
  v_key         text;
  v_esistente   hb_completions%ROWTYPE;
  v_quando      timestamp;
  v_fallimenti  integer;
  v_streak      integer;
  v_giorni      integer;
  v_goal        integer;
BEGIN
  IF NOT public.is_garsal() THEN
    RETURN jsonb_build_object('ok', false, 'error', 'non autorizzato');
  END IF;

  IF p_stato NOT IN ('completed', 'failed', 'skipped', 'none') THEN
    RETURN jsonb_build_object('ok', false, 'error', 'stato non ammesso: ' || p_stato);
  END IF;

  SELECT * INTO v_h FROM hb_habits WHERE id = p_habit_id;
  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'error', 'abitudine non trovata');
  END IF;

  v_key := hb_periodo_key(v_h.frequency, p_giorno, p_orario);

  -- La riga di quel periodo: per chiave, o — per le righe vecchie
  -- scritte prima che `period_key` esistesse — per data e orario.
  SELECT * INTO v_esistente
    FROM hb_completions c
   WHERE c.habit_id = p_habit_id
     AND (
       c.period_key = v_key
       OR (c.period_key IS NULL
           AND c.completed_at::date = p_giorno
           AND (p_orario IS NULL OR to_char(c.completed_at, 'HH24:MI') = p_orario))
     )
   ORDER BY c.completed_at DESC
   LIMIT 1;

  IF p_stato = 'none' THEN
    IF FOUND THEN
      DELETE FROM hb_completions WHERE id = v_esistente.id;
    END IF;
  ELSIF FOUND THEN
    IF v_esistente.status IS DISTINCT FROM p_stato THEN
      UPDATE hb_completions SET status = p_stato WHERE id = v_esistente.id;
    END IF;
  ELSE
    -- Mezzogiorno quando non c'è un orario: è l'ora che il web scrive,
    -- e tiene la riga dalla parte giusta della mezzanotte comunque si
    -- legga il fuso.
    v_quando := p_giorno + COALESCE(p_orario::time, TIME '12:00');
    INSERT INTO hb_completions (habit_id, completed_at, status, period_key, notes)
    VALUES (
      p_habit_id,
      v_quando,
      p_stato,
      v_key,
      CASE WHEN p_orario IS NULL THEN NULL ELSE p_stato || ' alle ' || p_orario END
    );
  END IF;

  -- I jolly si ricalcolano, non si contano a mano.
  v_fallimenti := hb_fallimenti(p_habit_id);
  IF COALESCE(v_h.current_failures, 0) IS DISTINCT FROM v_fallimenti THEN
    UPDATE hb_habits SET current_failures = v_fallimenti WHERE id = p_habit_id;
  END IF;

  v_streak := hb_streak(p_habit_id, p_oggi);
  v_goal   := COALESCE(v_h.goal, 0);
  v_giorni := hb_giorni_fatti(p_habit_id, COALESCE(p_oggi, CURRENT_DATE));

  RETURN jsonb_build_object(
    'ok',              true,
    'streak',          v_streak,
    'giorni_fatti',    v_giorni,
    'fallimenti',      v_fallimenti,
    'jolly_massimi',   COALESCE(v_h.max_failures, 0),
    'goal',            v_goal,
    'stack_da_chiudere', (v_goal > 0 AND (v_streak >= v_goal OR v_giorni >= v_goal)),
    'jolly_esauriti',  (COALESCE(v_h.max_failures, 0) > 0 AND v_fallimenti >= v_h.max_failures)
  );
END;
$$;

-- ── Il giro di riconciliazione ───────────────────────────────────────
--
-- Quello che nel web fanno `checkMissedDays`, `checkCompletedStacks` e
-- `checkExpiredStacks` a ogni disegno della dashboard, nello stesso
-- ordine e con gli stessi esiti:
--
--   1. i periodi passati senza nessuna riga diventano `missed`;
--   2. `current_failures` si riallinea al conteggio vero;
--   3. lo stack che ha raggiunto l'obiettivo si archivia come
--      `completato` e l'abitudine passa a `completed`;
--   4. lo stack scaduto per calendario si archivia — `completato_con_jolly`
--      se i giorni mancati stanno dentro i jolly, altrimenti
--      `scadenza_calendario` — e la riga di `hb_habits` si elimina;
--   5. lo stack che ha esaurito i jolly **si segnala e basta**: la
--      chiusura la decide l'utente nel modale.
--
-- Torna l'elenco di cosa è successo, perché il client possa mostrare le
-- sue cerimonie senza doverlo dedurre da sé.
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

      IF v_oggi > v_fine THEN
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


-- ── Ricominciare: lo stesso stack da una data nuova ──────────────────
--
-- Le colonne copiate sono quelle che `addHabit()` e `closeSuccessModal()`
-- scrivono nel web: quello che descrive **com'è fatta** l'abitudine. Non
-- si copia niente di quello che descrive **com'è andata** — i jolly
-- ripartono da zero e i completamenti restano attaccati allo stack
-- vecchio, che è appena finito in archivio.
CREATE OR REPLACE FUNCTION public.hb_clona(p_habit_id uuid, p_inizio date)
RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
DECLARE
  v_nuovo uuid;
BEGIN
  INSERT INTO hb_habits (
    name, description, category_id, frequency, weekdays, daily_times,
    goal, max_failures, points_reward, points_penalty, riservato,
    started_at, status, current_failures
  )
  SELECT h.name, h.description, h.category_id, h.frequency, h.weekdays, h.daily_times,
         h.goal, h.max_failures, h.points_reward, h.points_penalty, COALESCE(h.riservato, false),
         p_inizio, 'active', 0
    FROM hb_habits h
   WHERE h.id = p_habit_id
  RETURNING id INTO v_nuovo;

  RETURN v_nuovo;
END;
$$;

-- ── La chiusura che decide l'utente ──────────────────────────────────
--
-- È il modale «game over» di `habit-tracker.html` (`closeGameOver`), che
-- ha due uscite: **Interrompi** (`p_nuovo_inizio` nullo) archivia e
-- basta, **Ricomincia** archivia e riapre lo stack dalla data scelta.
--
-- ⚠️ L'archiviazione è **deduplicata** su `(habit_id, started_at)`, come
-- in `checkExpiredStacks`: `hb_reconcile()` può aver già archiviato uno
-- stack scaduto, e il modale che l'utente chiude dopo non deve
-- raddoppiare la riga né la penalità.
--
-- Il promemoria segue lo stack: se se ne apre uno nuovo la regola gli si
-- sposta dietro (l'orario lo aggiusta il web, che è l'unico dei due a
-- gestirli), se no se ne va con quello vecchio — o resterebbe agganciata
-- a una riga che non esiste più.
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
      points_earned, reason
    ) VALUES (
      v_h.id, v_h.name, v_h.category_id, v_h.started_at, v_oggi,
      v_streak, (v_oggi - v_inizio),
      (SELECT count(*) FROM hb_completions c
        WHERE c.habit_id = p_habit_id AND c.status = 'completed'
          AND c.completed_at::date >= v_inizio),
      (SELECT count(*) FROM hb_completions c
        WHERE c.habit_id = p_habit_id AND c.status IN ('failed', 'missed')
          AND c.completed_at::date >= v_inizio),
      -COALESCE(v_h.points_penalty, 0), 'jolly_esauriti'
    );
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

-- Le tabelle `hb_*` sono blindate su un solo account
-- (`20260611190000_lock_legacy_tables.sql`): queste funzioni girano come
-- proprietario, quindi il controllo su chi chiama lo fanno da sé con
-- `is_garsal()`. Senza, chiunque avesse la anon key potrebbe scrivere.
GRANT EXECUTE ON FUNCTION public.hb_lista(jsonb)                             TO authenticated;
GRANT EXECUTE ON FUNCTION public.hb_periodo_key(text, date, text)            TO authenticated;
GRANT EXECUTE ON FUNCTION public.hb_giorno_fatto(uuid, date)                 TO authenticated;
GRANT EXECUTE ON FUNCTION public.hb_streak(uuid, date)                       TO authenticated;
GRANT EXECUTE ON FUNCTION public.hb_giorni_fatti(uuid, date)                 TO authenticated;
GRANT EXECUTE ON FUNCTION public.hb_fallimenti(uuid)                         TO authenticated;
GRANT EXECUTE ON FUNCTION public.hb_set_completion(uuid, date, text, text, date) TO authenticated;
GRANT EXECUTE ON FUNCTION public.hb_reconcile(date)                          TO authenticated;
GRANT EXECUTE ON FUNCTION public.hb_clona(uuid, date)                        TO authenticated;
GRANT EXECUTE ON FUNCTION public.hb_chiudi_stack(uuid, date, date)           TO authenticated;
