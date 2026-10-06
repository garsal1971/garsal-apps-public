-- Il diario alimentare: il conto delle calorie in UNA regola, nel database.
--
-- Fino a qui la stessa regola viveva in due copie da tenere uguali a mano: `calcolaTarget`,
-- `stimaTratti`, `targetDelGiorno`, `targetTrattoAl`, `giorniDellaDieta` in weight-quest.html e
-- `CalorieRegole` nell'APK nativa. Il target di un giorno si CONGELA in `al_days` e da lì giudica
-- la giornata per sempre: due copie erano due numeri diversi congelati per lo stesso giorno a
-- seconda di quale app si apriva per prima.
--
--   al_calcola_target(p_giorno)            il conto di un giorno, com'è adesso
--   al_conto(p_oggi)                       tutto quel che le due app mostrano: il conto di ogni
--                                          giorno della finestra, i tratti del piano e il giorno
--                                          per giorno della dieta col saldo spalmato
--   al_congela_giorno(p_giorno)            scrive la riga di `al_days` se non c'è ancora
--   al_ricalcola_giorno(p_giorno)          la riscrive, su richiesta esplicita
--
-- Le regole, riga per riga quelle del JavaScript (vedi CLAUDE.md → Diario alimentare):
--   • basale Mifflin-St Jeor (M +5, F −161, «Altro» la via di mezzo) × `al_profile.activity`
--     (1,375 se manca) = consumo; il target è consumo − deficit, SENZA pavimenti;
--   • il deficit è la somma di DUE addendi — il ritmo del tratto (kg/giorno × 7700) e il
--     recupero dello scarto (peso vero − peso di piano) × 7700 / giorni che restano IN TUTTO —
--     mai sotto zero; senza curva o a piano finito, la media fino alla fine;
--   • l'ultimo traguardo vale come peso finale solo se i traguardi sono almeno due;
--   • nel giorno per giorno il saldo si spalma su TUTTI i giorni futuri, un giorno senza righe
--     non entra nel saldo, e da OGGI entra solo lo sforo.
--
-- ⚠️ Tre scelte prese il 25 settembre 2026, dove le copie non potevano coincidere:
--   • il peso di ripiego (giorno senza pesata) si cerca in TUTTE le pesate e non negli ultimi
--     120 giorni caricati dalla pagina, ed è il MINIMO dell'ultimo giorno pesato;
--   • la riga di `al_days` la scrive la RPC: il numero congelato nasce dalla regola unica;
--   • gli avvisi tornano come codice + numeri, e il testo resta nelle app.
--
-- ⚠️ SECURITY INVOKER: legge e scrive quel che la RLS lascia a chi chiama.
-- ⚠️ «Oggi» lo passa il client (`p_oggi`), come in `ps_punti`: il database sta in UTC.
-- ⚠️ `ps_objectives`, `ps_weight_tracking` e `cm_profile` non stanno in nessuna migration: si
-- leggono come jsonb invece di fidarsi del tipo delle colonne.

-- ── Il contesto: profilo, obiettivo attivo, traguardi ─────────────────────
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
    'ms',       CASE WHEN v_ob IS NULL THEN '[]'::jsonb ELSE ps_traguardi(v_ob->'milestones') END
  );
END $$;

-- ── Il metabolismo basale (Mifflin-St Jeor) ───────────────────────────────
-- NULL se manca un dato o l'età è fuori da 10–110: «non lo so» e non zero.
CREATE OR REPLACE FUNCTION public.al_basale(p_ctx jsonb, p_peso numeric, p_giorno date)
RETURNS numeric
LANGUAGE plpgsql IMMUTABLE SET search_path = public
AS $$
DECLARE
  v_nascita date := (p_ctx->>'nascita')::date;
  v_altezza numeric := (p_ctx->>'altezza')::numeric;
  v_sesso text := p_ctx->>'sesso';
  v_eta int;
BEGIN
  IF v_nascita IS NULL OR v_altezza IS NULL OR v_altezza = 0 OR v_sesso IS NULL
     OR p_peso IS NULL OR p_peso = 0 THEN RETURN NULL; END IF;
  -- L'età compiuta a quel giorno, dalla data di nascita.
  v_eta := extract(year FROM age(p_giorno, v_nascita))::int;
  IF v_eta < 10 OR v_eta > 110 THEN RETURN NULL; END IF;
  RETURN 10 * p_peso + 6.25 * v_altezza - 5 * v_eta
       + CASE v_sesso WHEN 'M' THEN 5 WHEN 'F' THEN -161 ELSE (5 + -161) / 2.0 END;
END $$;

-- ── Il peso di un giorno ──────────────────────────────────────────────────
-- Il MINIMO delle pesate di quel giorno; se non ce ne sono, il minimo dell'ultimo giorno pesato
-- PRIMA: il peso di domani non può entrare nel conto di ieri.
CREATE OR REPLACE FUNCTION public.al_peso_al(p_giorno date)
RETURNS numeric
LANGUAGE sql STABLE SECURITY INVOKER SET search_path = public
AS $$
  SELECT min((w->>'weight')::numeric)
    FROM (SELECT to_jsonb(t) w FROM ps_weight_tracking t) x
   WHERE (w->>'weight') IS NOT NULL
     AND (w->>'date')::date = (
       SELECT max((to_jsonb(t2)->>'date')::date) FROM ps_weight_tracking t2
        WHERE (to_jsonb(t2)->>'weight') IS NOT NULL
          AND (to_jsonb(t2)->>'date')::date <= p_giorno);
$$;

-- ── Il conto di un giorno, dato il contesto ───────────────────────────────
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

  v_peso := al_peso_al(p_giorno);
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

CREATE OR REPLACE FUNCTION public.al_calcola_target(p_giorno date)
RETURNS jsonb
LANGUAGE sql STABLE SECURITY INVOKER SET search_path = public
AS $$ SELECT al_calcola_target_ctx(al_contesto(), p_giorno) $$;

-- ── Il target da mostrare: quello congelato se c'è, altrimenti il conto di adesso ─
CREATE OR REPLACE FUNCTION public.al_target_del_giorno_ctx(p_ctx jsonb, p_giorno date)
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
  RETURN al_calcola_target_ctx(p_ctx, p_giorno) || jsonb_build_object('congelato', false);
END $$;

-- ── I tratti del piano, «se stai sul piano» ───────────────────────────────
-- Sul peso MEDIO che il piano prevede in quel tratto e SENZA il recupero dello scarto.
CREATE OR REPLACE FUNCTION public.al_tratti(p_ctx jsonb, p_oggi date)
RETURNS jsonb
LANGUAGE plpgsql IMMUTABLE SET search_path = public
AS $$
DECLARE
  v_ms jsonb := COALESCE(p_ctx->'ms', '[]'::jsonb);
  n int := jsonb_array_length(COALESCE(p_ctx->'ms', '[]'::jsonb));
  out jsonb := '[]'::jsonb;
  i int; da date; a date; gg int; pa numeric; pb numeric;
  v_bmr numeric; v_tdee numeric; v_kgg numeric; v_def numeric;
BEGIN
  IF n < 2 OR p_ctx->>'nascita' IS NULL OR p_ctx->>'altezza' IS NULL OR p_ctx->>'sesso' IS NULL THEN
    RETURN out;
  END IF;
  FOR i IN 0 .. n - 2 LOOP
    da := (v_ms->i->>'date')::date;   a := (v_ms->(i+1)->>'date')::date;
    gg := a - da;
    CONTINUE WHEN gg = 0;
    pa := (v_ms->i->>'weight')::numeric;  pb := (v_ms->(i+1)->>'weight')::numeric;
    v_bmr := al_basale(p_ctx, (pa + pb) / 2, da);
    v_tdee := v_bmr * (p_ctx->>'attivita')::numeric;
    v_kgg := (pa - pb) / gg;
    v_def := v_kgg * 7700;
    out := out || jsonb_build_array(jsonb_build_object(
      'numero', i + 1, 'inizio', da, 'fine', a, 'giorni', gg,
      'pesoInizio', pa, 'pesoFine', pb, 'kg', pa - pb, 'kgAlGiorno', v_kgg,
      'pesoMedio', (pa + pb) / 2, 'bmr', v_bmr, 'tdee', v_tdee,
      'deficit', v_def, 'target', v_tdee - v_def,
      'corrente', p_oggi >= da AND p_oggi < a, 'passato', p_oggi >= a));
  END LOOP;
  RETURN out;
END $$;

-- Il target del tratto che contiene un giorno; dopo la fine del piano vale l'ultimo tratto,
-- prima dell'inizio non c'è.
CREATE OR REPLACE FUNCTION public.al_tratto_al(p_tratti jsonb, p_giorno date)
RETURNS numeric
LANGUAGE sql IMMUTABLE SET search_path = public
AS $$
  SELECT COALESCE(
    (SELECT (t->>'target')::numeric FROM jsonb_array_elements(p_tratti) t
      WHERE p_giorno >= (t->>'inizio')::date AND p_giorno < (t->>'fine')::date LIMIT 1),
    (SELECT (p_tratti->-1->>'target')::numeric
      WHERE jsonb_array_length(p_tratti) > 0 AND p_giorno >= (p_tratti->-1->>'fine')::date));
$$;

-- ── Tutto quel che le due app mostrano ────────────────────────────────────
-- Torna:
--   { ok, finestra: {da, a},
--     tratti: [...],
--     giorni: { 'YYYY-MM-DD': { calcolato, target, tratto } }   ← da oggi−120 a oggi+62
--     piano:  { arco: {primo, ultimo, primoVero, tagliato} | null,
--               righe: [{giorno, kcal, target, scarto, futuro, riporto, oggi, peso, pesoPiano, righe}],
--               saldo, restanti, alGiorno } }
-- ⚠️ `calcolato` è il conto di adesso, `target` quello da mostrare (congelato se c'è): sono le
-- due domande diverse di `calcolaTarget` e `targetDelGiorno`.
-- ⚠️ La finestra è quella che la pagina carica (120 giorni indietro, `GIORNI_STORICO`), più due
-- mesi avanti per la prossima pesata: il diario non va oltre oggi.
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
    v_tgt := CASE WHEN g <= p_oggi THEN al_target_del_giorno_ctx(v_ctx, g)
                  ELSE v_calc || jsonb_build_object('congelato', false) END;
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
        v_tgt := al_target_del_giorno_ctx(v_ctx, g);
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
        'peso', CASE WHEN g > p_oggi THEN NULL ELSE al_peso_al(g) END,
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

-- ── Il congelamento ───────────────────────────────────────────────────────
-- La riga si scrive la prima volta e poi non si tocca: ON CONFLICT DO NOTHING, non un upsert.
-- Torna il target del giorno (congelato se la riga c'è) o `{ok:false, motivo}`.
CREATE OR REPLACE FUNCTION public.al_congela_giorno(p_giorno date)
RETURNS jsonb
LANGUAGE plpgsql VOLATILE SECURITY INVOKER SET search_path = public
AS $$
DECLARE
  v_ctx jsonb := al_contesto();
  c jsonb;
  v_ob_id text := v_ctx->'ob'->>'id';
BEGIN
  IF auth.uid() IS NULL THEN RETURN jsonb_build_object('ok', false, 'error', 'serve un login'); END IF;
  IF NOT EXISTS (SELECT 1 FROM al_days WHERE user_id = auth.uid() AND day = p_giorno) THEN
    c := al_calcola_target_ctx(v_ctx, p_giorno);
    IF NOT (c->>'ok')::boolean THEN RETURN c; END IF;
    INSERT INTO al_days (user_id, day, kcal_target, weight_kg, bmr, tdee, deficit_kcal, objective_id)
    VALUES (auth.uid(), p_giorno, round((c->>'kcal')::numeric), (c->>'peso')::numeric,
            round((c->>'bmr')::numeric), round((c->>'tdee')::numeric),
            round(COALESCE((c->>'deficit')::numeric, 0)),
            -- ⚠️ La colonna è uuid e l'id dell'obiettivo può essere un intero: si scrive solo se
            -- è davvero un uuid, invece di far fallire il congelamento.
            CASE WHEN v_ob_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
                 THEN v_ob_id::uuid END)
    ON CONFLICT (user_id, day) DO NOTHING;
  END IF;
  RETURN al_target_del_giorno_ctx(v_ctx, p_giorno);
END $$;

-- Riscrive la riga di un giorno, su richiesta esplicita: automatico riscriverebbe il passato.
CREATE OR REPLACE FUNCTION public.al_ricalcola_giorno(p_giorno date)
RETURNS jsonb
LANGUAGE plpgsql VOLATILE SECURITY INVOKER SET search_path = public
AS $$
DECLARE
  v_ctx jsonb := al_contesto();
  c jsonb := al_calcola_target_ctx(v_ctx, p_giorno);
  v_ob_id text := v_ctx->'ob'->>'id';
BEGIN
  IF auth.uid() IS NULL THEN RETURN jsonb_build_object('ok', false, 'error', 'serve un login'); END IF;
  IF NOT (c->>'ok')::boolean THEN RETURN c; END IF;
  UPDATE al_days SET
    kcal_target = round((c->>'kcal')::numeric), weight_kg = (c->>'peso')::numeric,
    bmr = round((c->>'bmr')::numeric), tdee = round((c->>'tdee')::numeric),
    deficit_kcal = round(COALESCE((c->>'deficit')::numeric, 0)),
    objective_id = CASE WHEN v_ob_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
                        THEN v_ob_id::uuid END,
    updated_at = now()
  WHERE user_id = auth.uid() AND day = p_giorno;
  RETURN al_target_del_giorno_ctx(v_ctx, p_giorno);
END $$;

REVOKE ALL ON FUNCTION public.al_contesto() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.al_basale(jsonb, numeric, date) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.al_peso_al(date) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.al_calcola_target_ctx(jsonb, date) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.al_calcola_target(date) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.al_target_del_giorno_ctx(jsonb, date) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.al_tratti(jsonb, date) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.al_tratto_al(jsonb, date) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.al_conto(date) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.al_congela_giorno(date) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.al_ricalcola_giorno(date) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.al_contesto() TO authenticated;
GRANT EXECUTE ON FUNCTION public.al_basale(jsonb, numeric, date) TO authenticated;
GRANT EXECUTE ON FUNCTION public.al_peso_al(date) TO authenticated;
GRANT EXECUTE ON FUNCTION public.al_calcola_target_ctx(jsonb, date) TO authenticated;
GRANT EXECUTE ON FUNCTION public.al_calcola_target(date) TO authenticated;
GRANT EXECUTE ON FUNCTION public.al_target_del_giorno_ctx(jsonb, date) TO authenticated;
GRANT EXECUTE ON FUNCTION public.al_tratti(jsonb, date) TO authenticated;
GRANT EXECUTE ON FUNCTION public.al_tratto_al(jsonb, date) TO authenticated;
GRANT EXECUTE ON FUNCTION public.al_conto(date) TO authenticated;
GRANT EXECUTE ON FUNCTION public.al_congela_giorno(date) TO authenticated;
GRANT EXECUTE ON FUNCTION public.al_ricalcola_giorno(date) TO authenticated;
