-- «Ti pisasti?»: i punti di un obiettivo e la sua chiusura, UNA regola nel database.
--
-- Fino a qui la stessa regola viveva in due copie da tenere uguali a mano:
-- `buildScoreRows()` / `milestoneAwards()` / `closeObjective()` in weight-quest.html e
-- `PesoRegole.righePunti` / `puntiTraguardiRaggiunti` / `PesoViewModel.preparaChiusura`
-- nell'APK nativa. Il punteggio di chiusura finisce in `ps_objectives.total_score`, e da lì
-- nel totale che paga i premi: due copie erano due punteggi diversi per lo stesso obiettivo
-- il giorno che una delle due cambiava.
--
-- ⚠️ Tre scelte, prese il 25 settembre 2026 dove le due copie NON coincidevano:
--   • le pesate considerate vanno da 90 giorni prima dell'inizio (servono solo a stimare i
--     primi giorni senza pesata) fino alla FINE dell'obiettivo, aperto o chiuso;
--   • un traguardo è raggiunto solo da una pesata fra inizio e fine dell'obiettivo;
--   • i punti totali dei traguardi sono quelli salvati in `ps_milestone_points`, non quelli
--     scritti nel form e non ancora salvati.
--
-- ⚠️ SECURITY INVOKER: legge e scrive quel che la RLS lascia a chi chiama.
-- ⚠️ «Oggi» lo passa il client (`p_oggi`): il database sta in UTC, e fra mezzanotte e le due
-- sarebbe ancora ieri — la stessa scelta di `task_complete` e `hb_reconcile`.
-- ⚠️ `ps_objectives` e `ps_weight_tracking` non stanno in nessuna migration: si leggono come
-- jsonb (`to_jsonb(riga)`) invece di fidarsi del tipo delle colonne, e l'id dell'obiettivo si
-- confronta come testo, come fa `ps_milestone_points.objective_id`.

-- ── Il peso che il piano chiede per un giorno ───────────────────────────
-- Interpolazione lineare fra i due traguardi che lo contengono, arrotondata a due decimali;
-- prima del primo e dopo l'ultimo vale il valore estremo; con meno di due traguardi non c'è.
-- È `pesoPianoAl()` di weight-quest.html e `PesoRegole.targetInterpolato` del nativo.
-- ⚠️ `p_ms` arriva già ordinato per data: lo ordina `ps_traguardi`.
CREATE OR REPLACE FUNCTION public.ps_peso_piano_al(p_ms jsonb, p_giorno date)
RETURNS numeric
LANGUAGE plpgsql IMMUTABLE SET search_path = public
AS $$
DECLARE
  n int := COALESCE(jsonb_array_length(p_ms), 0);
  i int;
  da date; a date; pa numeric; pb numeric;
BEGIN
  IF n < 2 THEN RETURN NULL; END IF;
  IF p_giorno <= (p_ms->0->>'date')::date THEN RETURN (p_ms->0->>'weight')::numeric; END IF;
  IF p_giorno >= (p_ms->(n-1)->>'date')::date THEN RETURN (p_ms->(n-1)->>'weight')::numeric; END IF;
  FOR i IN 0 .. n - 2 LOOP
    da := (p_ms->i->>'date')::date;
    a  := (p_ms->(i+1)->>'date')::date;
    IF p_giorno >= da AND p_giorno <= a THEN
      pa := (p_ms->i->>'weight')::numeric;
      pb := (p_ms->(i+1)->>'weight')::numeric;
      IF a = da THEN RETURN pa; END IF;
      RETURN round(pa + (pb - pa) * (p_giorno - da)::numeric / (a - da), 2);
    END IF;
  END LOOP;
  RETURN NULL;
END $$;

-- I traguardi di un obiettivo, ordinati per data. La colonna nasce da `JSON.stringify` nel
-- web, quindi può essere un testo con dentro del JSON o un jsonb che contiene una stringa:
-- si accettano tutti e due, come `Obiettivo.traguardiDa` nel nativo.
CREATE OR REPLACE FUNCTION public.ps_traguardi(p_raw jsonb)
RETURNS jsonb
LANGUAGE plpgsql IMMUTABLE SET search_path = public
AS $$
DECLARE
  v jsonb := p_raw;
BEGIN
  IF v IS NULL OR jsonb_typeof(v) = 'null' THEN RETURN '[]'::jsonb; END IF;
  IF jsonb_typeof(v) = 'string' THEN
    BEGIN v := (v #>> '{}')::jsonb; EXCEPTION WHEN others THEN RETURN '[]'::jsonb; END;
  END IF;
  IF jsonb_typeof(v) <> 'array' THEN RETURN '[]'::jsonb; END IF;
  RETURN COALESCE((
    SELECT jsonb_agg(jsonb_build_object('date', e->>'date', 'weight', (e->>'weight')::numeric)
                     ORDER BY (e->>'date')::date)
      FROM jsonb_array_elements(v) e
     WHERE e->>'date' IS NOT NULL AND e->>'weight' IS NOT NULL
  ), '[]'::jsonb);
END $$;

-- ── I punti di un obiettivo ─────────────────────────────────────────────
-- Torna:
--   { ok, rows: [{date, interp, missed, weight, target, points, cum}], total,
--     traguardi: { rows: [{threshold, points, reached, first_date}],
--                  reached, reached_points, total_points } }
--
-- Le regole, com'erano nelle due copie:
--   • senza N (`weigh_every_days` vuoto) ogni giorno fa punti, e i giorni senza pesata si
--     ricostruiscono interpolando fra la pesata prima e quella dopo: contano come gli altri;
--   • con N contano solo i giorni fissati (inizio, inizio+N, …): sul peso di quel giorno, e
--     malus se il giorno è passato senza pesata. Oggi senza pesata non è malus;
--   • il peso di una giornata è il suo minimo, e il target quello CONGELATO sulla riga
--     (`target_weight`), non ricalcolato: spostare i traguardi non riscrive il giudizio di ieri;
--   • il confronto peso/target si fa a un decimale: 79,44 contro 79,4 è vinta;
--   • un traguardo intermedio è raggiunto quando il minimo di una giornata scende sotto la
--     sua soglia; i punti totali si distribuiscono in modo crescente e l'ultima soglia prende
--     il residuo.
CREATE OR REPLACE FUNCTION public.ps_punti(p_objective_id text, p_oggi date)
RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY INVOKER SET search_path = public
AS $$
DECLARE
  o jsonb;
  ms jsonb;
  v_inizio date; v_fine date; v_fine_conto date;
  v_bonus int; v_malus int; v_ogni int;
  -- Le giornate con pesata, in ordine: giorno, minimo, target congelato.
  v_d date[]; v_m numeric[]; v_t numeric[];
  v_idx jsonb := '{}'::jsonb;
  n int; i int; j int; i_prima int; i_dopo int;
  g date;
  v_tgt numeric; v_peso numeric; v_pti int;
  v_somma int := 0;
  v_rows jsonb := '[]'::jsonb;
  -- traguardi
  v_sw numeric; v_ew numeric; v_top int; v_bot int; v_n int; t int; k int;
  v_tot_pts int; v_assegnati int := 0; v_p int; v_minimo numeric;
  v_trows jsonb := '[]'::jsonb; v_raggiunti int := 0; v_punti_ragg int := 0;
  v_first date; v_reached boolean;
BEGIN
  SELECT to_jsonb(x) INTO o FROM ps_objectives x WHERE x.id::text = p_objective_id;
  IF o IS NULL THEN
    RETURN jsonb_build_object('ok', false, 'error', 'Obiettivo non trovato.');
  END IF;

  ms := ps_traguardi(o->'milestones');
  v_inizio := NULLIF(o->>'start_date', '')::date;
  v_fine   := NULLIF(o->>'end_date', '')::date;
  -- I ripieghi di sempre: `obj.daily_bonus || 10`, cioè anche lo zero torna al default.
  v_bonus := COALESCE(NULLIF(round((o->>'daily_bonus')::numeric)::int, 0), 10);
  v_malus := COALESCE(NULLIF(round((o->>'daily_malus')::numeric)::int, 0), 5);
  v_ogni  := NULLIF(o->>'weigh_every_days', '')::numeric::int;
  IF v_ogni IS NOT NULL AND v_ogni < 1 THEN v_ogni := NULL; END IF;

  -- Le pesate dell'obiettivo: il minimo di ogni giornata col target congelato di quella
  -- pesata, da 90 giorni prima dell'inizio fino alla fine.
  IF v_inizio IS NOT NULL THEN
    SELECT array_agg(d ORDER BY d), array_agg(peso ORDER BY d), array_agg(tgt ORDER BY d)
      INTO v_d, v_m, v_t
      FROM (
        SELECT DISTINCT ON (d) d, peso, tgt
          FROM (
            SELECT (r->>'date')::date AS d,
                   (r->>'weight')::numeric AS peso,
                   NULLIF((r->>'target_weight')::numeric, 0) AS tgt,
                   (r->>'timestamp')::numeric AS ts
              FROM ps_weight_tracking w, LATERAL to_jsonb(w) r
             WHERE r->>'weight' IS NOT NULL AND r->>'date' IS NOT NULL
          ) s
         WHERE d >= v_inizio - 90 AND (v_fine IS NULL OR d <= v_fine)
         -- A pari peso vale la pesata più recente: è quella che il web prendeva.
         ORDER BY d, peso, ts DESC
      ) q;
  END IF;
  n := COALESCE(array_length(v_d, 1), 0);
  FOR i IN 1 .. n LOOP
    v_idx := v_idx || jsonb_build_object(v_d[i]::text, i);
  END LOOP;

  -- ── Il conto giorno per giorno ────────────────────────────────────────
  IF v_inizio IS NOT NULL AND jsonb_array_length(ms) >= 2 THEN
    v_fine_conto := LEAST(COALESCE(v_fine, p_oggi), p_oggi);
    g := v_inizio;
    WHILE g <= v_fine_conto LOOP
      -- Solo i giorni in cui ci si deve pesare: senza N tutti, con N quelli fissati.
      IF v_ogni IS NULL OR (g - v_inizio) % v_ogni = 0 THEN
        i := (v_idx->>(g::text))::int;
        IF i IS NOT NULL THEN
          -- Pesata vera: il target congelato sulla riga, o il piano se la riga non l'ha.
          v_tgt := COALESCE(v_t[i], ps_peso_piano_al(ms, g));
          v_peso := v_m[i];
          IF v_tgt IS NOT NULL AND v_peso IS NOT NULL THEN
            v_pti := CASE WHEN round(v_peso, 1) <= round(v_tgt, 1) THEN v_bonus ELSE -v_malus END;
            v_somma := v_somma + v_pti;
            v_rows := v_rows || jsonb_build_object('date', g, 'interp', false, 'missed', false,
              'weight', v_peso, 'target', v_tgt, 'points', v_pti, 'cum', v_somma);
          END IF;
        ELSIF v_ogni IS NOT NULL THEN
          -- Con N: giorno fissato passato senza pesata → malus. Oggi no, non è finito.
          IF g < p_oggi THEN
            v_tgt := ps_peso_piano_al(ms, g);
            v_somma := v_somma - v_malus;
            v_rows := v_rows || jsonb_build_object('date', g, 'interp', false, 'missed', true,
              'weight', NULL, 'target', v_tgt, 'points', -v_malus, 'cum', v_somma);
          END IF;
        ELSE
          -- Senza N: giorno senza pesata, ricostruito fra la pesata prima e quella dopo.
          v_tgt := ps_peso_piano_al(ms, g);
          i_prima := NULL; i_dopo := NULL;
          FOR j IN 1 .. n LOOP
            IF v_d[j] < g THEN i_prima := j;
            ELSIF v_d[j] > g THEN i_dopo := j; EXIT;
            END IF;
          END LOOP;
          IF i_prima IS NOT NULL AND i_dopo IS NOT NULL THEN
            v_peso := round(v_m[i_prima] + (v_m[i_dopo] - v_m[i_prima]) * (g - v_d[i_prima])::numeric / (v_d[i_dopo] - v_d[i_prima]), 2);
          ELSIF i_prima IS NOT NULL THEN
            v_peso := v_m[i_prima];
          ELSIF i_dopo IS NOT NULL THEN
            v_peso := v_m[i_dopo];
          ELSE
            v_peso := NULL;
          END IF;
          IF v_tgt IS NOT NULL AND v_peso IS NOT NULL THEN
            v_pti := CASE WHEN round(v_peso, 1) <= round(v_tgt, 1) THEN v_bonus ELSE -v_malus END;
            v_somma := v_somma + v_pti;
            v_rows := v_rows || jsonb_build_object('date', g, 'interp', true, 'missed', false,
              'weight', v_peso, 'target', v_tgt, 'points', v_pti, 'cum', v_somma);
          END IF;
        END IF;
      END IF;
      g := g + 1;
    END LOOP;
  END IF;

  -- ── I traguardi intermedi ─────────────────────────────────────────────
  v_sw := NULLIF(o->>'start_weight', '')::numeric;
  v_ew := NULLIF(o->>'end_weight', '')::numeric;
  SELECT total_points INTO v_tot_pts FROM ps_milestone_points WHERE objective_id = p_objective_id LIMIT 1;
  v_tot_pts := COALESCE(v_tot_pts, 0);
  IF v_sw IS NOT NULL AND v_ew IS NOT NULL AND v_sw > v_ew THEN
    v_top := floor(v_sw)::int - 1;
    v_bot := ceil(v_ew)::int;
    v_n := GREATEST(v_top - v_bot + 1, 0);
    -- Il minimo delle giornate fra inizio e fine: una soglia scatta quando ci passa sotto.
    FOR j IN 1 .. n LOOP
      IF v_d[j] >= v_inizio AND (v_fine IS NULL OR v_d[j] <= v_fine)
         AND (v_minimo IS NULL OR v_m[j] < v_minimo) THEN
        v_minimo := v_m[j];
      END IF;
    END LOOP;
    k := 0;
    t := v_top;
    WHILE t >= v_bot LOOP
      -- Distribuzione crescente: la soglia più difficile vale di più, l'ultima prende il
      -- residuo per non perdere punti negli arrotondamenti.
      IF v_tot_pts > 0 THEN
        IF k < v_n - 1 THEN
          v_p := round(v_tot_pts * (k + 1)::numeric / (v_n * (v_n + 1) / 2.0))::int;
        ELSE
          v_p := v_tot_pts - v_assegnati;
        END IF;
        v_assegnati := v_assegnati + v_p;
      ELSE
        v_p := 0;
      END IF;
      v_reached := v_minimo IS NOT NULL AND v_minimo <= t;
      v_first := NULL;
      IF v_reached THEN
        v_raggiunti := v_raggiunti + 1;
        v_punti_ragg := v_punti_ragg + v_p;
        FOR j IN 1 .. n LOOP
          IF v_d[j] >= v_inizio AND (v_fine IS NULL OR v_d[j] <= v_fine) AND v_m[j] <= t THEN
            v_first := v_d[j]; EXIT;
          END IF;
        END LOOP;
      END IF;
      v_trows := v_trows || jsonb_build_object('threshold', t, 'points', v_p,
                   'reached', v_reached, 'first_date', v_first);
      k := k + 1;
      t := t - 1;
    END LOOP;
  END IF;

  RETURN jsonb_build_object(
    'ok', true,
    'rows', v_rows,
    'total', v_somma,
    'traguardi', jsonb_build_object('rows', v_trows, 'reached', v_raggiunti,
                   'reached_points', v_punti_ragg, 'total_points', v_tot_pts));
END $$;

-- ── La chiusura di un obiettivo ─────────────────────────────────────────
-- `p_conferma = false` controlla e fa il conto, senza scrivere: è quel che la pagina mostra
-- nella conferma. `true` rifà gli stessi controlli e scrive stato e punteggio. Il conto si
-- rifà alla conferma e non si prende dal client: fra le due chiamate può essere arrivata una
-- pesata.
--
-- Il punteggio: punti giornalieri + (solo col successo) i punti dei traguardi RAGGIUNTI —
-- raggiunti e non grattati: dimenticarsi di toccare una stellina non deve costare punti —
-- + bonus finale col successo, − malus finale col fallimento.
--
-- Chiudere con successo vuole:
--   • «mantenere»: il peso massimo del periodo [inizio, fine] non sopra il peso stabilito;
--   • «perdere»: il minimo di OGGI non sopra il peso finale — una pesata di oggi, non vecchia.
CREATE OR REPLACE FUNCTION public.ps_chiudi_obiettivo(
  p_objective_id text, p_stato text, p_oggi date, p_conferma boolean DEFAULT false)
RETURNS jsonb
LANGUAGE plpgsql SECURITY INVOKER SET search_path = public
AS $$
DECLARE
  o jsonb;
  v_ew numeric; v_inizio date; v_fine date;
  v_val numeric;
  v_punti jsonb;
  v_giorn int; v_trag int; v_chiu int; v_tot int;
  v_n int;
BEGIN
  IF p_stato NOT IN ('success', 'failed') THEN
    RETURN jsonb_build_object('ok', false, 'error', 'Stato di chiusura non valido.');
  END IF;
  SELECT to_jsonb(x) INTO o FROM ps_objectives x WHERE x.id::text = p_objective_id;
  IF o IS NULL THEN
    RETURN jsonb_build_object('ok', false, 'error', 'Obiettivo non trovato.');
  END IF;
  IF o->>'status' IN ('success', 'failed') THEN
    RETURN jsonb_build_object('ok', false, 'error', 'Questo obiettivo è già chiuso!');
  END IF;

  IF p_stato = 'success' THEN
    v_ew := NULLIF(o->>'end_weight', '')::numeric;
    IF v_ew IS NULL THEN
      RETURN jsonb_build_object('ok', false,
        'error', 'Obiettivo senza peso target valido: impossibile chiudere con SUCCESSO.');
    END IF;
    v_inizio := NULLIF(o->>'start_date', '')::date;
    v_fine   := NULLIF(o->>'end_date', '')::date;
    IF o->>'objective_type' = 'mantenere' THEN
      SELECT max((r->>'weight')::numeric) INTO v_val
        FROM ps_weight_tracking w, LATERAL to_jsonb(w) r
       WHERE r->>'weight' IS NOT NULL
         AND (r->>'date')::date >= v_inizio AND (r->>'date')::date <= v_fine;
      IF v_val IS NULL THEN
        RETURN jsonb_build_object('ok', false, 'error',
          format('Non puoi chiudere con SUCCESSO: nessun peso registrato nel periodo (%s → %s).',
                 to_char(v_inizio, 'DD/MM/YYYY'), to_char(v_fine, 'DD/MM/YYYY')));
      END IF;
      IF v_val > v_ew THEN
        RETURN jsonb_build_object('ok', false, 'error',
          format('Non puoi chiudere con SUCCESSO: il massimo del periodo (%s kg) supera il peso stabilito (%s kg).',
                 replace(round(v_val, 1)::text, '.', ','), replace(round(v_ew, 1)::text, '.', ',')));
      END IF;
    ELSE
      SELECT min((r->>'weight')::numeric) INTO v_val
        FROM ps_weight_tracking w, LATERAL to_jsonb(w) r
       WHERE r->>'weight' IS NOT NULL AND (r->>'date')::date = p_oggi;
      IF v_val IS NULL THEN
        RETURN jsonb_build_object('ok', false, 'error',
          format('Non puoi chiudere con SUCCESSO: nessun peso registrato oggi (%s).',
                 to_char(p_oggi, 'DD/MM/YYYY')));
      END IF;
      IF v_val > v_ew THEN
        RETURN jsonb_build_object('ok', false, 'error',
          format('Non puoi chiudere con SUCCESSO: il minimo di oggi (%s kg) supera l''obiettivo (%s kg).',
                 replace(round(v_val, 1)::text, '.', ','), replace(round(v_ew, 1)::text, '.', ',')));
      END IF;
    END IF;
  END IF;

  v_punti := ps_punti(p_objective_id, p_oggi);
  IF NOT (v_punti->>'ok')::boolean THEN RETURN v_punti; END IF;
  v_giorn := (v_punti->>'total')::int;
  v_trag  := CASE WHEN p_stato = 'success' THEN (v_punti->'traguardi'->>'reached_points')::int ELSE 0 END;
  v_chiu  := CASE WHEN p_stato = 'success'
                  THEN COALESCE(NULLIF(round((o->>'final_bonus')::numeric)::int, 0), 100)
                  ELSE -COALESCE(NULLIF(round((o->>'final_malus')::numeric)::int, 0), 50) END;
  v_tot   := v_giorn + v_trag + v_chiu;

  IF p_conferma THEN
    UPDATE ps_objectives SET status = p_stato, total_score = v_tot
     WHERE id::text = p_objective_id AND COALESCE(status, '') NOT IN ('success', 'failed');
    GET DIAGNOSTICS v_n = ROW_COUNT;
    IF v_n = 0 THEN
      RETURN jsonb_build_object('ok', false, 'error', 'Obiettivo non chiuso: nessuna riga aggiornata.');
    END IF;
  END IF;

  RETURN jsonb_build_object(
    'ok', true,
    'chiuso', p_conferma,
    'stato', p_stato,
    'punti_giornalieri', v_giorn,
    'punti_traguardi', v_trag,
    'traguardi_raggiunti', (v_punti->'traguardi'->>'reached')::int,
    'traguardi_totali', jsonb_array_length(v_punti->'traguardi'->'rows'),
    'punti_chiusura', v_chiu,
    'totale', v_tot);
END $$;

REVOKE ALL ON FUNCTION public.ps_peso_piano_al(jsonb, date) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.ps_traguardi(jsonb) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.ps_punti(text, date) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.ps_chiudi_obiettivo(text, text, date, boolean) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.ps_peso_piano_al(jsonb, date) TO authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.ps_traguardi(jsonb) TO authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.ps_punti(text, date) TO authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.ps_chiudi_obiettivo(text, text, date, boolean) TO authenticated, service_role;
