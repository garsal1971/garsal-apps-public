-- Il diario alimentare: `al_conto` andava in «statement timeout».
--
-- Il sintomo (29 settembre 2026, APK nativa e weight-quest.html): «Calorie non calcolate:
-- canceling statement due to statement timeout». `al_conto` fa il conto di ogni giorno da oggi−120
-- a oggi+62 — 183 giorni — e per i giorni passati lo rifaceva una seconda volta dentro
-- `al_target_del_giorno_ctx`, e una terza nel giorno per giorno della dieta. Ogni conto chiamava
-- `al_peso_al`, che rilegge TUTTA `ps_weight_tracking` due volte convertendo ogni riga in jsonb:
-- qualche centinaio di scansioni complete della tabella per una chiamata sola, contro gli 8
-- secondi che PostgREST concede al ruolo `authenticated`.
--
-- La correzione non cambia nessuna regola, solo quante volte si legge:
--   • `al_pesi()` legge le pesate UNA volta — il minimo di ogni giorno pesato, dal più recente —
--     e `al_contesto()` le porta con sé nella chiave `pesi`;
--   • `al_peso_ctx(ctx, giorno)` cerca lì dentro lo stesso numero di `al_peso_al` (il minimo del
--     giorno, o dell'ultimo giorno pesato prima); senza `pesi` nel contesto ripiega su di lei;
--   • in `al_conto` il target di un giorno passato non congelato è il conto già fatto, e il giorno
--     per giorno rilegge quel che la finestra ha già calcolato.
-- `al_peso_al` resta com'è: la usa chi chiede il peso di un giorno solo.

-- ── Le pesate, lette una volta ────────────────────────────────────────────
-- [{d: 'YYYY-MM-DD', w: minimo del giorno}, …], dal giorno più recente al più vecchio.
CREATE OR REPLACE FUNCTION public.al_pesi()
RETURNS jsonb
LANGUAGE sql STABLE SECURITY INVOKER SET search_path = public
AS $$
  SELECT COALESCE(jsonb_agg(jsonb_build_object('d', d::text, 'w', w) ORDER BY d DESC), '[]'::jsonb)
    FROM (SELECT (x->>'date')::date d, min((x->>'weight')::numeric) w
            FROM (SELECT to_jsonb(t) x FROM ps_weight_tracking t) s
           WHERE (x->>'weight') IS NOT NULL AND (x->>'date') IS NOT NULL
           GROUP BY 1) g;
$$;

-- Il peso di un giorno dalle pesate del contesto: la stessa regola di `al_peso_al`.
CREATE OR REPLACE FUNCTION public.al_peso_ctx(p_ctx jsonb, p_giorno date)
RETURNS numeric
LANGUAGE plpgsql STABLE SECURITY INVOKER SET search_path = public
AS $$
DECLARE
  v_g text := p_giorno::text;
  e jsonb;
BEGIN
  IF p_ctx IS NULL OR NOT (p_ctx ? 'pesi') THEN RETURN al_peso_al(p_giorno); END IF;
  -- In ordine decrescente: il primo giorno non successivo è quello giusto. Le date ISO si
  -- confrontano come testo.
  FOR e IN SELECT v FROM jsonb_array_elements(p_ctx->'pesi') v LOOP
    IF e->>'d' <= v_g THEN RETURN (e->>'w')::numeric; END IF;
  END LOOP;
  RETURN NULL;
END $$;

-- Il target congelato di un giorno, o NULL: la prima metà di `al_target_del_giorno_ctx`.
CREATE OR REPLACE FUNCTION public.al_congelato(p_ctx jsonb, p_giorno date)
RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY INVOKER SET search_path = public
AS $$
DECLARE
  r al_days%ROWTYPE;
BEGIN
  SELECT * INTO r FROM al_days WHERE user_id = auth.uid() AND day = p_giorno;
  IF FOUND AND r.kcal_target IS NOT NULL THEN
    RETURN jsonb_build_object('ok', true, 'congelato', true, 'motivo', 'congelato',
      'kcal', r.kcal_target, 'bmr', r.bmr, 'tdee', r.tdee, 'deficit', COALESCE(r.deficit_kcal, 0),
      'peso', r.weight_kg, 'pesoPiano', ps_peso_piano_al(COALESCE(p_ctx->'ms', '[]'::jsonb), p_giorno));
  END IF;
  RETURN NULL;
END $$;

-- ── Il contesto, ora con le pesate ────────────────────────────────────────
CREATE OR REPLACE FUNCTION public.al_contesto()
RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY INVOKER SET search_path = public
AS $$
DECLARE
  v_cm  jsonb;
  v_att numeric;
  v_ob  jsonb;
BEGIN
  SELECT to_jsonb(c) INTO v_cm FROM cm_profile c WHERE c.user_id = auth.uid() LIMIT 1;
  SELECT NULLIF(p.activity, 0) INTO v_att FROM al_profile p WHERE p.user_id = auth.uid() LIMIT 1;
  -- L'ultimo obiettivo ATTIVO, come la pagina: quelli chiusi non dettano il target di oggi.
  SELECT to_jsonb(o) INTO v_ob FROM ps_objectives o
   WHERE o.status = 'active' ORDER BY o.created_at DESC NULLS LAST LIMIT 1;

  RETURN jsonb_build_object(
    'nascita',  NULLIF(v_cm->>'data_nascita', ''),
    'altezza',  NULLIF(v_cm->>'altezza_cm', '')::numeric,
    'sesso',    NULLIF(v_cm->>'sesso', ''),
    'attivita', COALESCE(v_att, 1.375),
    'ob',       v_ob,
    -- ⚠️ Le pesate, lette UNA volta: vedi al_pesi.
    'pesi',     al_pesi(),
    'ms',       CASE WHEN v_ob IS NULL THEN '[]'::jsonb ELSE ps_traguardi(v_ob->'milestones') END
  );
END $$;

-- ── Il conto di un giorno: il peso dal contesto ───────────────────────────
CREATE OR REPLACE FUNCTION public.al_calcola_target_ctx(p_ctx jsonb, p_giorno date)
RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY INVOKER SET search_path = public
AS $$
DECLARE
  KCAL_PER_KG constant numeric := 7700;
  v_ms jsonb := COALESCE(p_ctx->'ms', '[]'::jsonb);
  n int := jsonb_array_length(COALESCE(p_ctx->'ms', '[]'::jsonb));
  v_ob jsonb := p_ctx->'ob';
  v_peso numeric; v_bmr numeric; v_tdee numeric;
  v_piano numeric; v_finale numeric; v_fine date; v_rim int;
  v_kg numeric; v_def numeric := 0; v_kcal numeric;
  v_seg jsonb; v_def_piano numeric; v_scarto numeric; v_rec numeric;
  v_soglia numeric; v_avviso jsonb; v_sotto boolean := false;
  i int; k int; da date; a date; gg int;
BEGIN
  IF p_ctx->>'nascita' IS NULL OR p_ctx->>'altezza' IS NULL OR p_ctx->>'sesso' IS NULL THEN
    RETURN jsonb_build_object('ok', false, 'motivo', 'profilo', 'sottoSoglia', false);
  END IF;

  v_peso := al_peso_ctx(p_ctx, p_giorno);
  IF v_peso IS NULL THEN RETURN jsonb_build_object('ok', false, 'motivo', 'peso', 'sottoSoglia', false); END IF;

  v_bmr := al_basale(p_ctx, v_peso, p_giorno);
  IF v_bmr IS NULL THEN RETURN jsonb_build_object('ok', false, 'motivo', 'profilo', 'peso', v_peso, 'sottoSoglia', false); END IF;
  v_tdee := v_bmr * (p_ctx->>'attivita')::numeric;

  -- Senza obiettivo attivo non si inventa un dimagrimento: il target è il mantenimento.
  IF v_ob IS NULL THEN
    RETURN jsonb_build_object('ok', true, 'motivo', 'mantenimento', 'kcal', v_tdee, 'bmr', v_bmr,
      'tdee', v_tdee, 'deficit', 0, 'peso', v_peso, 'sottoSoglia', false);
  END IF;

  v_piano := ps_peso_piano_al(v_ms, p_giorno);
  -- ⚠️ L'ultimo traguardo vale come peso finale solo se i traguardi sono almeno DUE.
  v_finale := CASE WHEN n >= 2 THEN (v_ms->(n-1)->>'weight')::numeric
                   ELSE NULLIF(v_ob->>'end_weight', '')::numeric END;
  v_fine := COALESCE(NULLIF(v_ob->>'end_date', '')::date,
                     CASE WHEN n > 0 THEN (v_ms->(n-1)->>'date')::date END);
  -- Almeno un giorno: a fine obiettivo si dividerebbe per zero.
  v_rim := CASE WHEN v_fine IS NULL THEN NULL ELSE greatest(1, v_fine - p_giorno) END;

  IF v_finale IS NULL OR v_rim IS NULL THEN
    RETURN jsonb_build_object('ok', true, 'motivo', 'mantenimento', 'kcal', v_tdee, 'bmr', v_bmr,
      'tdee', v_tdee, 'deficit', 0, 'peso', v_peso, 'pesoPiano', v_piano, 'pesoFinale', v_finale,
      'giorniRimasti', v_rim, 'sottoSoglia', false);
  END IF;

  v_kg := greatest(0, v_peso - v_finale);

  -- Il tratto del piano che contiene il giorno (NULL senza curva o a piano finito).
  IF n >= 2 AND p_giorno < (v_ms->(n-1)->>'date')::date THEN
    i := 0;
    FOR k IN 0 .. n - 2 LOOP
      IF p_giorno >= (v_ms->k->>'date')::date AND p_giorno < (v_ms->(k+1)->>'date')::date THEN
        i := k; EXIT;
      END IF;
    END LOOP;
    da := (v_ms->i->>'date')::date;
    a  := (v_ms->(i+1)->>'date')::date;
    gg := a - da;
    IF gg <> 0 THEN
      v_seg := jsonb_build_object(
        'inizio', da, 'fine', a,
        'pesoInizio', (v_ms->i->>'weight')::numeric, 'pesoFine', (v_ms->(i+1)->>'weight')::numeric,
        'giorni', gg, 'numero', i + 1, 'quanti', n - 1,
        'kgAlGiorno', ((v_ms->i->>'weight')::numeric - (v_ms->(i+1)->>'weight')::numeric) / gg);
    END IF;
  END IF;

  IF v_seg IS NOT NULL AND v_piano IS NOT NULL THEN
    -- Il ritmo del tratto + il recupero dello scarto spalmato sui giorni che restano IN TUTTO.
    v_def_piano := (v_seg->>'kgAlGiorno')::numeric * KCAL_PER_KG;
    v_scarto := v_peso - v_piano;
    v_rec := v_scarto * KCAL_PER_KG / v_rim;
    -- Mai sotto zero: essere avanti allenta il target fino al mantenimento, non oltre.
    v_def := greatest(0, v_def_piano + v_rec);
  ELSE
    v_def := v_kg * KCAL_PER_KG / v_rim;
  END IF;

  -- Senza pavimenti: se il piano chiede troppo, si vede.
  v_kcal := v_tdee - v_def;

  -- ⚠️ Solo un avviso, e il testo lo scrivono le app: qui il codice e i numeri.
  v_soglia := CASE p_ctx->>'sesso' WHEN 'M' THEN 1500 WHEN 'F' THEN 1200 ELSE 1500 END;
  IF v_kcal <= 0 THEN
    v_sotto := true;  v_avviso := jsonb_build_object('codice', 'negativo', 'soglia', v_soglia);
  ELSIF v_kcal < v_soglia THEN
    v_sotto := true;  v_avviso := jsonb_build_object('codice', 'sotto_soglia', 'soglia', v_soglia);
  ELSIF v_def > 1000 THEN
    v_avviso := jsonb_build_object('codice', 'deficit_alto', 'soglia', v_soglia);
  END IF;

  RETURN jsonb_build_object(
    'ok', true, 'motivo', CASE WHEN v_kg > 0 THEN 'obiettivo' ELSE 'raggiunto' END,
    'kcal', v_kcal, 'bmr', v_bmr, 'tdee', v_tdee, 'deficit', v_def,
    'peso', v_peso, 'pesoPiano', v_piano, 'pesoFinale', v_finale,
    'giorniRimasti', v_rim, 'kgDaPerdere', v_kg,
    'segmento', v_seg, 'deficitPiano', v_def_piano, 'scartoKg', v_scarto, 'recupero', v_rec,
    'sottoSoglia', v_sotto, 'avviso', v_avviso);
END $$;

-- ── Tutto quel che le due app mostrano, senza rifare i conti ─────────────
CREATE OR REPLACE FUNCTION public.al_conto(p_oggi date)
RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY INVOKER SET search_path = public
AS $$
DECLARE
  STORICO constant int := 120;
  v_ctx jsonb := al_contesto();
  v_ms jsonb := COALESCE(v_ctx->'ms', '[]'::jsonb);
  n int := jsonb_array_length(COALESCE(v_ctx->'ms', '[]'::jsonb));
  v_ob jsonb := v_ctx->'ob';
  v_tratti jsonb := al_tratti(v_ctx, p_oggi);
  v_da date := p_oggi - STORICO;
  v_a date := p_oggi + 62;
  v_giorni jsonb := '{}'::jsonb;
  v_calc jsonb; v_tgt jsonb;
  g date;
  v_primo date; v_ultimo date; v_primo_vero date; v_arco jsonb;
  v_righe jsonb := '[]'::jsonb; v_kcal numeric; v_nrighe int; v_t numeric; v_scarto numeric;
  v_saldo numeric := 0; v_fut int := 0; v_al numeric := 0; v_base numeric;
  v_out jsonb := '[]'::jsonb; r jsonb;
BEGIN
  -- Il conto di ogni giorno della finestra.
  g := v_da;
  WHILE g <= v_a LOOP
    v_calc := al_calcola_target_ctx(v_ctx, g);
    -- Il congelato se c'è, altrimenti lo stesso conto appena fatto: rifarlo costava il doppio.
    v_tgt := NULL;
    IF g <= p_oggi THEN v_tgt := al_congelato(v_ctx, g); END IF;
    v_tgt := COALESCE(v_tgt, v_calc || jsonb_build_object('congelato', false));
    v_giorni := v_giorni || jsonb_build_object(g::text, jsonb_build_object(
      'calcolato', v_calc, 'target', v_tgt, 'tratto', al_tratto_al(v_tratti, g)));
    g := g + 1;
  END LOOP;

  -- L'arco della dieta: i traguardi quando ci sono, altrimenti le date dell'obiettivo; il primo
  -- giorno tirato in avanti fino alla finestra.
  IF v_ob IS NOT NULL THEN
    v_primo_vero := COALESCE(CASE WHEN n > 0 THEN (v_ms->0->>'date')::date END,
                             NULLIF(v_ob->>'start_date', '')::date);
    v_ultimo := COALESCE(CASE WHEN n >= 2 THEN (v_ms->(n-1)->>'date')::date END,
                         CASE WHEN n < 2 THEN NULLIF(v_ob->>'end_date', '')::date END);
  END IF;

  IF v_primo_vero IS NOT NULL AND v_ultimo IS NOT NULL THEN
    v_primo := greatest(v_primo_vero, v_da);
    v_arco := jsonb_build_object('primo', v_primo, 'ultimo', v_ultimo,
                                 'primoVero', v_primo_vero, 'tagliato', v_primo_vero < v_da);

    -- Primo giro: quel che è già successo.
    g := v_primo;
    WHILE g <= v_ultimo LOOP
      SELECT sum(l.kcal_100g * l.grams / 100), count(*) INTO v_kcal, v_nrighe
        FROM al_log l WHERE l.user_id = auth.uid() AND l.day = g;
      IF v_nrighe = 0 THEN v_kcal := NULL; END IF;
      v_t := NULL;
      IF g <= p_oggi THEN
        -- Già nella finestra (il primo giorno dell'arco non le sta prima): si rilegge da lì.
        v_tgt := COALESCE(v_giorni->(g::text)->'target', al_target_del_giorno_ctx(v_ctx, g));
        IF (v_tgt->>'ok')::boolean THEN v_t := (v_tgt->>'kcal')::numeric; END IF;
      END IF;
      v_scarto := CASE WHEN v_kcal IS NOT NULL AND v_t IS NOT NULL THEN v_kcal - v_t END;
      -- ⚠️ Da OGGI entra solo lo sforo: un diario a metà non è un digiuno.
      IF g <= p_oggi AND v_scarto IS NOT NULL THEN
        v_saldo := v_saldo + CASE WHEN g = p_oggi THEN greatest(0, v_scarto) ELSE v_scarto END;
      END IF;
      IF g > p_oggi THEN v_fut := v_fut + 1; END IF;
      v_righe := v_righe || jsonb_build_array(jsonb_build_object(
        'giorno', g, 'kcal', v_kcal, 'target', v_t, 'scarto', v_scarto,
        'futuro', g > p_oggi, 'riporto', NULL, 'oggi', g = p_oggi,
        -- Nel futuro il peso non si trascina: sembrerebbe una previsione.
        'peso', CASE WHEN g > p_oggi THEN NULL ELSE al_peso_ctx(v_ctx, g) END,
        'pesoPiano', ps_peso_piano_al(v_ms, g), 'righe', v_nrighe));
      g := g + 1;
    END LOOP;

    -- Secondo giro: il saldo spalmato su TUTTI i giorni che restano, la stessa fetta per tutti.
    v_al := CASE WHEN v_fut > 0 THEN v_saldo / v_fut ELSE 0 END;
    FOR r IN SELECT * FROM jsonb_array_elements(v_righe) LOOP
      IF (r->>'futuro')::boolean THEN
        v_base := al_tratto_al(v_tratti, (r->>'giorno')::date);
        IF v_base IS NOT NULL THEN
          r := r || jsonb_build_object('riporto', -v_al, 'target', round(v_base - v_al));
        END IF;
      END IF;
      v_out := v_out || jsonb_build_array(r);
    END LOOP;
  END IF;

  RETURN jsonb_build_object(
    'ok', true,
    'finestra', jsonb_build_object('da', v_da, 'a', v_a),
    'tratti', v_tratti,
    'giorni', v_giorni,
    'piano', jsonb_build_object('arco', v_arco, 'righe', v_out, 'saldo', v_saldo,
                                'restanti', v_fut, 'alGiorno', v_al));
END $$;

REVOKE ALL ON FUNCTION public.al_pesi() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.al_peso_ctx(jsonb, date) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.al_congelato(jsonb, date) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.al_pesi() TO authenticated;
GRANT EXECUTE ON FUNCTION public.al_peso_ctx(jsonb, date) TO authenticated;
GRANT EXECUTE ON FUNCTION public.al_congelato(jsonb, date) TO authenticated;
