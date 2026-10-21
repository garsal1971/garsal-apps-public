-- I punti di «Ti pisasti?» si danno sulla MEDIA MOBILE del peso (web v4.17.0).
--
-- La bilancia balla troppo per giudicare la singola pesata: ogni giorno che conta (tutti, o
-- quelli fissati da weigh_every_days) confronta ps_daily_ema.ema_weight di quel giorno — vera o
-- stimata dalle pesate della finestra — col target congelato sulla pesata o, senza pesata,
-- con quello del piano. Senza nessuna media e a giorno passato: malus, come non essersi pesati.
-- La riga porta anche 'giorno', il minimo vero di quel giorno, perché le app lo mostrino accanto.
--
-- Restano sul peso VERO del giorno (il minimo) le ⭐ stelline dei traguardi intermedi.
-- Chiusura: «perdere» sulla media di oggi (serve la pesata di oggi), «mantenere» sulla media più
-- alta del periodo. La massa grassa non c'è più (20261021100000): use_fat_mass non si guarda.
-- Nessuna colonna cambia: si riscrivono le due funzioni.

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
  v_grasso boolean;
  v_magra numeric;
  -- la media mobile (solo sulla massa grassa)
  v_ema jsonb := '{}'::jsonb;
  e jsonb;
BEGIN
  SELECT to_jsonb(x) INTO o FROM ps_objectives x WHERE x.id::text = p_objective_id;
  IF o IS NULL THEN
    RETURN jsonb_build_object('ok', false, 'error', 'Obiettivo non trovato.');
  END IF;

  ms := ps_traguardi(o->'milestones');
  -- v4.16 web: la massa grassa non c'è più, i punti sono sempre sulla media del peso.
  v_grasso := false;
  v_inizio := NULLIF(o->>'start_date', '')::date;
  v_fine   := NULLIF(o->>'end_date', '')::date;
  v_bonus := COALESCE(NULLIF(round((o->>'daily_bonus')::numeric)::int, 0), 10);
  v_malus := COALESCE(NULLIF(round((o->>'daily_malus')::numeric)::int, 0), 5);
  v_ogni  := NULLIF(o->>'weigh_every_days', '')::numeric::int;
  IF v_ogni IS NOT NULL AND v_ogni < 1 THEN v_ogni := NULL; END IF;

  -- Le pesate dell'obiettivo: il minimo di ogni giornata col target congelato di quella
  -- pesata, da 90 giorni prima dell'inizio fino alla fine. Sulla massa grassa servono i soli
  -- target congelati (il valore è la media mobile), quindi si legge il peso totale.
  IF v_inizio IS NOT NULL THEN
    SELECT array_agg(d ORDER BY d), array_agg(peso ORDER BY d), array_agg(tgt ORDER BY d)
      INTO v_d, v_m, v_t
      FROM (
        SELECT DISTINCT ON (d) d, peso, tgt
          FROM (
            SELECT (r->>'date')::date AS d,
                   ps_peso_conta(r, false) AS peso,
                   NULLIF((r->>'target_weight')::numeric, 0) AS tgt,
                   (r->>'timestamp')::numeric AS ts
              FROM ps_weight_tracking w, LATERAL to_jsonb(w) r
             WHERE r->>'weight' IS NOT NULL AND r->>'date' IS NOT NULL
          ) s
         WHERE d >= v_inizio - 90 AND (v_fine IS NULL OR d <= v_fine)
           AND peso IS NOT NULL
         ORDER BY d, peso, ts DESC
      ) q;
  END IF;
  n := COALESCE(array_length(v_d, 1), 0);
  FOR i IN 1 .. n LOOP
    v_idx := v_idx || jsonb_build_object(v_d[i]::text, i);
  END LOOP;

  -- La media mobile del peso, giorno per giorno: è lei che fa i punti.
  IF v_inizio IS NOT NULL THEN
    SELECT COALESCE(jsonb_object_agg(day::text, jsonb_build_object(
             'ema', ema_weight, 'stimato', weight_stimato)), '{}'::jsonb)
      INTO v_ema
      FROM ps_daily_ema
     WHERE user_id = auth.uid() AND day >= v_inizio AND (v_fine IS NULL OR day <= v_fine);
  END IF;

  -- ── Il conto giorno per giorno ────────────────────────────────────────
  IF v_inizio IS NOT NULL AND jsonb_array_length(ms) >= 2 THEN
    v_fine_conto := LEAST(COALESCE(v_fine, p_oggi), p_oggi);
    g := v_inizio;
    WHILE g <= v_fine_conto LOOP
      IF v_ogni IS NULL OR (g - v_inizio) % v_ogni = 0 THEN
        i := (v_idx->>(g::text))::int;
        -- ⚠️ Conta la MEDIA MOBILE del peso di quel giorno (vera o stimata dalle pesate della
        -- finestra), contro il target congelato sulla pesata o, senza pesata, quello del piano.
        v_tgt := COALESCE(CASE WHEN i IS NOT NULL THEN v_t[i] END, ps_peso_piano_al(ms, g));
        e := v_ema->(g::text);
        v_peso := NULLIF(e->>'ema', '')::numeric;
        IF v_peso IS NOT NULL AND v_tgt IS NOT NULL THEN
          v_pti := CASE WHEN round(v_peso, 1) <= round(v_tgt, 1) THEN v_bonus ELSE -v_malus END;
          v_somma := v_somma + v_pti;
          v_rows := v_rows || jsonb_build_object('date', g,
            'interp', COALESCE((e->>'stimato')::boolean, false), 'missed', false,
            'weight', round(v_peso, 2), 'target', v_tgt, 'points', v_pti, 'cum', v_somma, 'ema', true,
            'giorno', CASE WHEN i IS NOT NULL THEN v_m[i] END);
        ELSIF v_peso IS NULL AND g < p_oggi THEN
          -- Nessuna media: è come non essersi pesati. Oggi no, non è finito.
          v_somma := v_somma - v_malus;
          v_rows := v_rows || jsonb_build_object('date', g, 'interp', false, 'missed', true,
            'weight', NULL, 'target', v_tgt, 'points', -v_malus, 'cum', v_somma, 'ema', true);
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
    -- ⚠️ Sulla massa grassa le stelline vanno sul PESO GIORNALIERO VERO: la prima pesata di
    -- ogni giorno (non la media, non i giorni stimati), la prima volta che scende sotto.
    IF v_grasso THEN
      SELECT min(weight) INTO v_minimo
        FROM ps_daily_ema
       WHERE user_id = auth.uid() AND NOT weight_stimato AND weight IS NOT NULL
         AND day >= v_inizio AND (v_fine IS NULL OR day <= v_fine);
    ELSE
      FOR j IN 1 .. n LOOP
        IF v_d[j] >= v_inizio AND (v_fine IS NULL OR v_d[j] <= v_fine)
           AND (v_minimo IS NULL OR v_m[j] < v_minimo) THEN
          v_minimo := v_m[j];
        END IF;
      END LOOP;
    END IF;
    k := 0;
    t := v_top;
    WHILE t >= v_bot LOOP
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
        IF v_grasso THEN
          SELECT min(day) INTO v_first
            FROM ps_daily_ema
           WHERE user_id = auth.uid() AND NOT weight_stimato AND weight IS NOT NULL AND weight <= t
             AND day >= v_inizio AND (v_fine IS NULL OR day <= v_fine);
        ELSE
          FOR j IN 1 .. n LOOP
            IF v_d[j] >= v_inizio AND (v_fine IS NULL OR v_d[j] <= v_fine) AND v_m[j] <= t THEN
              v_first := v_d[j]; EXIT;
            END IF;
          END LOOP;
        END IF;
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
    'ema', true,
    'traguardi', jsonb_build_object('rows', v_trows, 'reached', v_raggiunti,
                   'reached_points', v_punti_ragg, 'total_points', v_tot_pts));
END $$;

-- ── La chiusura ──
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
  v_grasso boolean;
  v_cosa text;
  v_nessuna text;
  v_magra numeric;
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

  v_cosa := ' della media mobile';
  v_nessuna := 'nessuna media mobile';

  IF p_stato = 'success' THEN
    v_ew := NULLIF(o->>'end_weight', '')::numeric;
    IF v_ew IS NULL THEN
      RETURN jsonb_build_object('ok', false,
        'error', 'Obiettivo senza peso target valido: impossibile chiudere con SUCCESSO.');
    END IF;
    v_inizio := NULLIF(o->>'start_date', '')::date;
    v_fine   := NULLIF(o->>'end_date', '')::date;
    IF o->>'objective_type' = 'mantenere' THEN
      SELECT max(ema_weight) INTO v_val FROM ps_daily_ema
       WHERE user_id = auth.uid() AND day >= v_inizio AND day <= v_fine;
      IF v_val IS NULL THEN
        RETURN jsonb_build_object('ok', false, 'error',
          format('Non puoi chiudere con SUCCESSO: %s nel periodo (%s → %s).',
                 v_nessuna, to_char(v_inizio, 'DD/MM/YYYY'), to_char(v_fine, 'DD/MM/YYYY')));
      END IF;
      IF v_val > v_ew THEN
        RETURN jsonb_build_object('ok', false, 'error',
          format('Non puoi chiudere con SUCCESSO: il massimo%s del periodo (%s kg) supera il peso stabilito (%s kg).',
                 v_cosa, replace(round(v_val, 1)::text, '.', ','), replace(round(v_ew, 1)::text, '.', ',')));
      END IF;
    ELSE
      SELECT ema_weight INTO v_val FROM ps_daily_ema
       WHERE user_id = auth.uid() AND day = p_oggi AND NOT weight_stimato;
      IF v_val IS NULL THEN
        RETURN jsonb_build_object('ok', false, 'error',
          format('Non puoi chiudere con SUCCESSO: %s oggi (%s).',
                 'nessuna pesata',
                 to_char(p_oggi, 'DD/MM/YYYY')));
      END IF;
      IF v_val > v_ew THEN
        RETURN jsonb_build_object('ok', false, 'error',
          format('Non puoi chiudere con SUCCESSO: il valore%s di oggi (%s kg) supera l''obiettivo (%s kg).',
                 v_cosa, replace(round(v_val, 1)::text, '.', ','), replace(round(v_ew, 1)::text, '.', ',')));
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

