-- «Ti pisasti?»: la MASSA GRASSA prende il posto del peso a secco.
--
-- Renpho scrive in Health Connect peso e grasso corporeo, ma NON l'acqua: il peso a secco
-- (20261005100000 / 20261005120000) non poteva ricevere i dati. Lo si sostituisce con la
-- massa grassa, che è anche quel che una dieta vuole far scendere:
--
--   massa grassa = peso × %grasso / 100
--
-- ps_weight_tracking.body_fat_pct  il grasso corporeo (%) di quella pesata, come lo scrive la
--                                  bilancia. NULL = non misurato, non zero.
-- ps_objectives.use_fat_mass       l'obiettivo conta punti e chiusura sulla massa grassa.
-- ps_objectives.fat_pct            la % di grasso «normale», FISSA per tutto l'obiettivo: i
--                                  traguardi si scrivono in peso TOTALE e il target di grasso
--                                  di un giorno è quello × fat_pct / 100.
--
-- Come per il secco: le stelline dei traguardi intermedi restano sul peso TOTALE, il diario
-- delle calorie pure (al_contesto non cambia), `target_weight` sulle righe resta il target
-- TOTALE congelato e il fattore si applica leggendolo. Senza grasso una pesata non conta.
--
-- ⚠️ Le colonne del secco si tolgono (water_kg, use_dry_weight, water_pct): sono nate oggi e
-- nessuna bilancia di casa le riempie. Un obiettivo che era a secco torna sul peso totale.

ALTER TABLE ps_weight_tracking ADD COLUMN IF NOT EXISTS body_fat_pct numeric;
ALTER TABLE ps_objectives ADD COLUMN IF NOT EXISTS use_fat_mass boolean NOT NULL DEFAULT false;
ALTER TABLE ps_objectives ADD COLUMN IF NOT EXISTS fat_pct numeric;

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint
                  WHERE conname = 'ps_weight_tracking_body_fat_pct_check'
                    AND conrelid = 'public.ps_weight_tracking'::regclass) THEN
    ALTER TABLE ps_weight_tracking
      ADD CONSTRAINT ps_weight_tracking_body_fat_pct_check
      CHECK (body_fat_pct IS NULL OR (body_fat_pct > 0 AND body_fat_pct < 100));
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint
                  WHERE conname = 'ps_objectives_fat_pct_check'
                    AND conrelid = 'public.ps_objectives'::regclass) THEN
    ALTER TABLE ps_objectives
      ADD CONSTRAINT ps_objectives_fat_pct_check
      CHECK (fat_pct IS NULL OR (fat_pct >= 3 AND fat_pct <= 70));
  END IF;
END $$;

ALTER TABLE ps_weight_tracking DROP COLUMN IF EXISTS water_kg;
ALTER TABLE ps_objectives DROP COLUMN IF EXISTS use_dry_weight;
ALTER TABLE ps_objectives DROP COLUMN IF EXISTS water_pct;

-- Il peso che conta di una pesata: il peso, oppure la massa grassa.
-- ⚠️ Gemella di `massaGrassa()` in weight-quest.html: cambiandone una, cambia l'altra.
DROP FUNCTION IF EXISTS public.ps_peso_conta(jsonb, boolean);
CREATE FUNCTION public.ps_peso_conta(p_riga jsonb, p_grasso boolean)
RETURNS numeric
LANGUAGE plpgsql IMMUTABLE SET search_path = public
AS $$
DECLARE
  v_p numeric := NULLIF(p_riga->>'weight', '')::numeric;
  v_g numeric;
BEGIN
  IF v_p IS NULL OR NOT COALESCE(p_grasso, false) THEN RETURN v_p; END IF;
  v_g := NULLIF(p_riga->>'body_fat_pct', '')::numeric;
  IF v_g IS NULL OR v_g <= 0 OR v_g >= 100 THEN RETURN NULL; END IF;
  RETURN round(v_p * v_g / 100, 2);
END $$;

-- ── I punti ──
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
  v_fatt numeric := 1;
BEGIN
  SELECT to_jsonb(x) INTO o FROM ps_objectives x WHERE x.id::text = p_objective_id;
  IF o IS NULL THEN
    RETURN jsonb_build_object('ok', false, 'error', 'Obiettivo non trovato.');
  END IF;

  ms := ps_traguardi(o->'milestones');
  -- ⚠️ Massa grassa: quel che conta è peso × %grasso della pesata (`ps_peso_conta`).
  v_grasso := COALESCE((o->>'use_fat_mass')::boolean, false);
  -- I traguardi si scrivono in peso TOTALE: il target di grasso è quello × %grasso,
  -- con la % fissa scritta sull'obiettivo. Senza % si conta sul totale come prima.
  IF v_grasso AND NULLIF(o->>'fat_pct', '') IS NOT NULL THEN
    v_fatt := (o->>'fat_pct')::numeric / 100;
  ELSE
    v_grasso := false;
  END IF;
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
                   ps_peso_conta(r, v_grasso) AS peso,
                   NULLIF((r->>'target_weight')::numeric, 0) AS tgt,
                   (r->>'timestamp')::numeric AS ts
              FROM ps_weight_tracking w, LATERAL to_jsonb(w) r
             WHERE r->>'weight' IS NOT NULL AND r->>'date' IS NOT NULL
          ) s
         WHERE d >= v_inizio - 90 AND (v_fine IS NULL OR d <= v_fine)
           -- Sulla massa grassa, una pesata senza grasso non conta: è come non essersi pesati.
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
          v_tgt := CASE WHEN NOT v_grasso THEN COALESCE(v_t[i], ps_peso_piano_al(ms, g)) ELSE round((COALESCE(v_t[i], ps_peso_piano_al(ms, g))) * v_fatt, 2) END;
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
            v_tgt := CASE WHEN NOT v_grasso THEN ps_peso_piano_al(ms, g) ELSE round((ps_peso_piano_al(ms, g)) * v_fatt, 2) END;
            v_somma := v_somma - v_malus;
            v_rows := v_rows || jsonb_build_object('date', g, 'interp', false, 'missed', true,
              'weight', NULL, 'target', v_tgt, 'points', -v_malus, 'cum', v_somma);
          END IF;
        ELSE
          -- Senza N: giorno senza pesata, ricostruito fra la pesata prima e quella dopo.
          v_tgt := CASE WHEN NOT v_grasso THEN ps_peso_piano_al(ms, g) ELSE round((ps_peso_piano_al(ms, g)) * v_fatt, 2) END;
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
    -- ⚠️ Sulla massa grassa le stelline restano sul peso TOTALE: soglie e minimo si leggono lì.
    IF v_grasso THEN
      SELECT min((r->>'weight')::numeric) INTO v_minimo
        FROM ps_weight_tracking w, LATERAL to_jsonb(w) r
       WHERE r->>'weight' IS NOT NULL AND (r->>'date')::date >= v_inizio
         AND (v_fine IS NULL OR (r->>'date')::date <= v_fine);
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
        IF v_grasso THEN
          SELECT min((r->>'date')::date) INTO v_first
            FROM ps_weight_tracking w, LATERAL to_jsonb(w) r
           WHERE r->>'weight' IS NOT NULL AND (r->>'weight')::numeric <= t
             AND (r->>'date')::date >= v_inizio
             AND (v_fine IS NULL OR (r->>'date')::date <= v_fine);
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

  v_grasso := COALESCE((o->>'use_fat_mass')::boolean, false)
             AND NULLIF(o->>'fat_pct', '') IS NOT NULL;
  v_cosa := CASE WHEN v_grasso THEN ' di massa grassa' ELSE '' END;
  v_nessuna := CASE WHEN v_grasso THEN 'nessuna pesata col grasso corporeo registrata' ELSE 'nessun peso registrato' END;

  IF p_stato = 'success' THEN
    v_ew := NULLIF(o->>'end_weight', '')::numeric;
    IF v_ew IS NULL THEN
      RETURN jsonb_build_object('ok', false,
        'error', 'Obiettivo senza peso target valido: impossibile chiudere con SUCCESSO.');
    END IF;
    -- Il peso finale è scritto in peso totale: la massa grassa si confronta con quello × %grasso.
    IF v_grasso THEN
      v_ew := round(v_ew * (o->>'fat_pct')::numeric / 100, 2);
    END IF;
    v_inizio := NULLIF(o->>'start_date', '')::date;
    v_fine   := NULLIF(o->>'end_date', '')::date;
    IF o->>'objective_type' = 'mantenere' THEN
      SELECT max(ps_peso_conta(r, v_grasso)) INTO v_val
        FROM ps_weight_tracking w, LATERAL to_jsonb(w) r
       WHERE r->>'weight' IS NOT NULL
         AND (r->>'date')::date >= v_inizio AND (r->>'date')::date <= v_fine;
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
      SELECT min(ps_peso_conta(r, v_grasso)) INTO v_val
        FROM ps_weight_tracking w, LATERAL to_jsonb(w) r
       WHERE r->>'weight' IS NOT NULL AND (r->>'date')::date = p_oggi;
      IF v_val IS NULL THEN
        RETURN jsonb_build_object('ok', false, 'error',
          format('Non puoi chiudere con SUCCESSO: %s oggi (%s).',
                 v_nessuna, to_char(p_oggi, 'DD/MM/YYYY')));
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


GRANT EXECUTE ON FUNCTION public.ps_peso_conta(jsonb, boolean) TO authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.ps_punti(text, date) TO authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.ps_chiudi_obiettivo(text, text, date, boolean) TO authenticated, service_role;
