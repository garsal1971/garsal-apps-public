-- «Ti pisasti?»: il PESO A SECCO.
--
-- La bilancia (Renpho, via Health Connect) scrive accanto al peso anche la massa d'acqua
-- corporea. Il peso a secco è peso − massa d'acqua, cioè peso × (1 − %acqua), ed è il peso
-- con cui un obiettivo può decidere di contare punti, traguardi e chiusura. Il peso totale
-- resta scritto e si mostra, ma per un obiettivo a secco serve solo a titolo informativo.
--
-- ps_weight_tracking.water_kg     la massa d'acqua di quella pesata, in kg, com'è arrivata da
--                                 Health Connect (BodyWaterMassRecord). NULL = non si sa: una
--                                 pesata manuale senza %, o una bilancia che non la misura.
--                                 Si archiviano i kg e non la %: è quel che la fonte dà, e la %
--                                 si ricava (water_kg / weight).
-- ps_objectives.use_dry_weight    l'obiettivo si misura a secco. DEFAULT false: gli obiettivi
--                                 già in archivio restano esattamente come erano. Traguardi,
--                                 peso iniziale e peso finale di un obiettivo a secco si
--                                 scrivono A SECCO, a mano: non si convertono da soli.
--
-- ⚠️ Per un obiettivo a secco una pesata senza acqua NON conta: per i punti è come non essersi
-- pesati (ricostruita senza N, malus con N). Inventarle un'acqua darebbe un peso a secco falso.
-- ⚠️ L'APK nativa per ora non conosce il peso a secco: chiama le stesse RPC e quindi riceve
-- punti e chiusura giusti, ma la sua tabella e il suo grafico mostrano ancora il peso totale.

ALTER TABLE ps_weight_tracking ADD COLUMN IF NOT EXISTS water_kg numeric;
ALTER TABLE ps_objectives ADD COLUMN IF NOT EXISTS use_dry_weight boolean NOT NULL DEFAULT false;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
     WHERE conname = 'ps_weight_tracking_water_kg_check'
       AND conrelid = 'public.ps_weight_tracking'::regclass
  ) THEN
    ALTER TABLE ps_weight_tracking
      ADD CONSTRAINT ps_weight_tracking_water_kg_check CHECK (water_kg IS NULL OR water_kg > 0);
  END IF;
END $$;

-- ── Il peso che conta per un obiettivo ──────────────────────────────────
-- Il peso della riga, oppure — per un obiettivo a secco — peso − acqua, a due decimali.
-- NULL se a secco manca l'acqua, o se il conto non dà un peso sensato.
-- È `pesoSecco()` di weight-quest.html: la stessa formula, da cambiare insieme.
CREATE OR REPLACE FUNCTION public.ps_peso_conta(p_riga jsonb, p_secco boolean)
RETURNS numeric
LANGUAGE plpgsql IMMUTABLE SET search_path = public
AS $$
DECLARE
  v_p numeric := NULLIF(p_riga->>'weight', '')::numeric;
  v_a numeric;
BEGIN
  IF v_p IS NULL OR NOT COALESCE(p_secco, false) THEN RETURN v_p; END IF;
  v_a := NULLIF(p_riga->>'water_kg', '')::numeric;
  IF v_a IS NULL OR v_a <= 0 OR v_a >= v_p THEN RETURN NULL; END IF;
  RETURN round(v_p - v_a, 2);
END $$;

-- ── I punti: come prima, sul peso che conta ─────────────────────────────
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
  v_secco boolean;
BEGIN
  SELECT to_jsonb(x) INTO o FROM ps_objectives x WHERE x.id::text = p_objective_id;
  IF o IS NULL THEN
    RETURN jsonb_build_object('ok', false, 'error', 'Obiettivo non trovato.');
  END IF;

  ms := ps_traguardi(o->'milestones');
  -- ⚠️ Peso a secco: il peso che conta è peso − massa d'acqua (`ps_peso_conta`).
  v_secco := COALESCE((o->>'use_dry_weight')::boolean, false);
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
                   ps_peso_conta(r, v_secco) AS peso,
                   NULLIF((r->>'target_weight')::numeric, 0) AS tgt,
                   (r->>'timestamp')::numeric AS ts
              FROM ps_weight_tracking w, LATERAL to_jsonb(w) r
             WHERE r->>'weight' IS NOT NULL AND r->>'date' IS NOT NULL
          ) s
         WHERE d >= v_inizio - 90 AND (v_fine IS NULL OR d <= v_fine)
           -- A secco, una pesata senza acqua non conta: è come non essersi pesati.
           AND peso IS NOT NULL
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

-- ── La chiusura: i due controlli sul peso che conta ─────────────────────
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
  v_secco boolean;
  v_cosa text;
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

  v_secco := COALESCE((o->>'use_dry_weight')::boolean, false);
  v_cosa := CASE WHEN v_secco THEN ' a secco' ELSE '' END;

  IF p_stato = 'success' THEN
    v_ew := NULLIF(o->>'end_weight', '')::numeric;
    IF v_ew IS NULL THEN
      RETURN jsonb_build_object('ok', false,
        'error', 'Obiettivo senza peso target valido: impossibile chiudere con SUCCESSO.');
    END IF;
    v_inizio := NULLIF(o->>'start_date', '')::date;
    v_fine   := NULLIF(o->>'end_date', '')::date;
    IF o->>'objective_type' = 'mantenere' THEN
      SELECT max(ps_peso_conta(r, v_secco)) INTO v_val
        FROM ps_weight_tracking w, LATERAL to_jsonb(w) r
       WHERE r->>'weight' IS NOT NULL
         AND (r->>'date')::date >= v_inizio AND (r->>'date')::date <= v_fine;
      IF v_val IS NULL THEN
        RETURN jsonb_build_object('ok', false, 'error',
          format('Non puoi chiudere con SUCCESSO: nessun peso%s registrato nel periodo (%s → %s).',
                 v_cosa, to_char(v_inizio, 'DD/MM/YYYY'), to_char(v_fine, 'DD/MM/YYYY')));
      END IF;
      IF v_val > v_ew THEN
        RETURN jsonb_build_object('ok', false, 'error',
          format('Non puoi chiudere con SUCCESSO: il massimo%s del periodo (%s kg) supera il peso stabilito (%s kg).',
                 v_cosa, replace(round(v_val, 1)::text, '.', ','), replace(round(v_ew, 1)::text, '.', ',')));
      END IF;
    ELSE
      SELECT min(ps_peso_conta(r, v_secco)) INTO v_val
        FROM ps_weight_tracking w, LATERAL to_jsonb(w) r
       WHERE r->>'weight' IS NOT NULL AND (r->>'date')::date = p_oggi;
      IF v_val IS NULL THEN
        RETURN jsonb_build_object('ok', false, 'error',
          format('Non puoi chiudere con SUCCESSO: nessun peso%s registrato oggi (%s).',
                 v_cosa, to_char(p_oggi, 'DD/MM/YYYY')));
      END IF;
      IF v_val > v_ew THEN
        RETURN jsonb_build_object('ok', false, 'error',
          format('Non puoi chiudere con SUCCESSO: il minimo%s di oggi (%s kg) supera l''obiettivo (%s kg).',
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

-- ── Il contesto del diario: i traguardi a secco riportati a peso totale ─
CREATE OR REPLACE FUNCTION public.al_contesto()
RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY INVOKER SET search_path = public
AS $$
DECLARE
  v_cm  jsonb;
  v_att numeric;
  v_ob  jsonb;
  v_ms  jsonb;
  v_acqua numeric;
BEGIN
  SELECT to_jsonb(c) INTO v_cm FROM cm_profile c WHERE c.user_id = auth.uid() LIMIT 1;
  SELECT NULLIF(p.activity, 0) INTO v_att FROM al_profile p WHERE p.user_id = auth.uid() LIMIT 1;
  -- L'ultimo obiettivo ATTIVO, come la pagina: quelli chiusi non dettano il target di oggi.
  SELECT to_jsonb(o) INTO v_ob FROM ps_objectives o
   WHERE o.status = 'active' ORDER BY o.created_at DESC NULLS LAST LIMIT 1;

  v_ms := CASE WHEN v_ob IS NULL THEN '[]'::jsonb ELSE ps_traguardi(v_ob->'milestones') END;

  -- ⚠️ Obiettivo a secco: traguardi e peso finale sono pesi a secco, mentre il conto delle
  -- calorie lavora sul peso totale (il basale di Mifflin-St Jeor vuole il peso vero). Si
  -- riportano quindi a peso totale aggiungendo l'ultima massa d'acqua nota: lo scarto peso −
  -- piano resta così lo scarto a secco. Senza nessuna acqua in archivio non si inventa
  -- niente: il diario torna al mantenimento, come senza obiettivo.
  IF v_ob IS NOT NULL AND COALESCE((v_ob->>'use_dry_weight')::boolean, false) THEN
    SELECT (x->>'water_kg')::numeric INTO v_acqua
      FROM (SELECT to_jsonb(t) x FROM ps_weight_tracking t) s
     WHERE x->>'water_kg' IS NOT NULL AND x->>'weight' IS NOT NULL
     ORDER BY (x->>'timestamp')::numeric DESC NULLS LAST
     LIMIT 1;
    IF v_acqua IS NULL THEN
      v_ob := NULL;
      v_ms := '[]'::jsonb;
    ELSE
      v_ms := COALESCE((
        SELECT jsonb_agg(jsonb_build_object('date', e->>'date',
                         'weight', round((e->>'weight')::numeric + v_acqua, 2))
                         ORDER BY (e->>'date')::date)
          FROM jsonb_array_elements(v_ms) e), '[]'::jsonb);
      IF NULLIF(v_ob->>'end_weight', '') IS NOT NULL THEN
        v_ob := v_ob || jsonb_build_object('end_weight', round((v_ob->>'end_weight')::numeric + v_acqua, 2));
      END IF;
    END IF;
  END IF;

  RETURN jsonb_build_object(
    'nascita',  NULLIF(v_cm->>'data_nascita', ''),
    'altezza',  NULLIF(v_cm->>'altezza_cm', '')::numeric,
    'sesso',    NULLIF(v_cm->>'sesso', ''),
    'attivita', COALESCE(v_att, 1.375),
    'ob',       v_ob,
    -- ⚠️ Le pesate, lette UNA volta: vedi al_pesi.
    'pesi',     al_pesi(),
    'ms',       v_ms,
    -- Solo informativi: l'acqua aggiunta ai traguardi di un obiettivo a secco.
    'acqua',    v_acqua
  );
END $$;


REVOKE ALL ON FUNCTION public.ps_peso_conta(jsonb, boolean) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.ps_peso_conta(jsonb, boolean) TO authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.ps_punti(text, date) TO authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.ps_chiudi_obiettivo(text, text, date, boolean) TO authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.al_contesto() TO authenticated;
