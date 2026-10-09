-- «Ti pisasti?»: la MEDIA MOBILE ESPONENZIALE di peso, massa grassa e % di grasso (web v4.12.0).
--
-- La bilancia a impedenza balla: ±1 punto di % su 90 kg è quasi un chilo di grasso, e i punti
-- giornalieri sulla massa grassa della singola pesata andavano su e giù a caso. Ora:
--
--   • ps_daily_ema           una riga per giorno: il valore del giorno (la PRIMA pesata della
--                            giornata), le tre medie mobili esponenziali e il loro trend;
--   • cm_settings.ps_ema_days  N della media (di partenza 7), α = 2 / (N + 1). Si cambia da
--                            ⚙️ Impostazioni, e cambiandolo si ricalcola tutto;
--   • ps_ema_ricalcola()     rifà la tabella da capo per l'utente; la chiama il trigger su
--                            ps_weight_tracking dopo ogni scrittura, e la pagina dopo un cambio di N.
--
-- Il giorno senza pesata si STIMA col trend: valore = media + trend, e la media si aggiorna su
-- quella stima (riga marcata stimata). Il trend è la media mobile delle variazioni giornaliere
-- della media. Un buco di più di 14 giorni non si stima: la serie riparte alla pesata successiva,
-- o un trend estrapolato per mesi inventerebbe chili che nessuno ha misurato.
--
-- La massa grassa media è la media dei KG giornalieri (peso × % della stessa pesata), non il
-- prodotto delle due medie.
--
-- Cosa cambia nei punti (solo per gli obiettivi sulla massa grassa, `use_fat_mass`):
--   • punti giornalieri: nei giorni di pesata (ogni N, o tutti) si confronta la MEDIA della massa
--     grassa con il grasso previsto. Un giorno di pesata passato senza nessun valore — né vero
--     né stimato — è malus;
--   • stelline dei traguardi intermedi: sul PESO GIORNALIERO VERO (la prima pesata del giorno),
--     la prima volta che va sotto la soglia — non sulla media;
--   • chiusura: «perdere» guarda la media di oggi, «mantenere» la media più alta del periodo.
-- Gli obiettivi sul peso totale non cambiano. Il total_score degli obiettivi già chiusi è
-- congelato sulla riga e non si tocca.

-- ── La tabella ───────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.ps_daily_ema (
  user_id          uuid NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
  day              date NOT NULL,
  -- Il valore del giorno: vero (prima pesata) o stimato col trend.
  weight           numeric,
  fat_pct          numeric,
  fat_kg           numeric,
  weight_stimato   boolean NOT NULL DEFAULT false,
  fat_stimato      boolean NOT NULL DEFAULT false,
  -- Le medie mobili esponenziali e il trend (kg o punti % al giorno).
  ema_weight       numeric,
  ema_fat_pct      numeric,
  ema_fat_kg       numeric,
  trend_weight     numeric,
  trend_fat_pct    numeric,
  trend_fat_kg     numeric,
  ema_days         int NOT NULL,
  computed_at      timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, day)
);

ALTER TABLE public.ps_daily_ema ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS ps_daily_ema_owner ON public.ps_daily_ema;
CREATE POLICY ps_daily_ema_owner ON public.ps_daily_ema
  FOR ALL USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());
GRANT SELECT, INSERT, UPDATE, DELETE ON public.ps_daily_ema TO authenticated;
GRANT ALL ON public.ps_daily_ema TO service_role;

-- ── N della media ────────────────────────────────────────────────────────
CREATE OR REPLACE FUNCTION public.ps_ema_giorni()
RETURNS int
LANGUAGE plpgsql STABLE SECURITY INVOKER SET search_path = public
AS $$
DECLARE v_txt text; v_n int;
BEGIN
  BEGIN
    SELECT value::text INTO v_txt FROM cm_settings WHERE key = 'ps_ema_days' LIMIT 1;
    v_n := round(NULLIF(trim(both '"' from v_txt), '')::numeric)::int;
  EXCEPTION WHEN OTHERS THEN v_n := NULL;
  END;
  IF v_n IS NULL OR v_n < 2 THEN RETURN 7; END IF;
  RETURN LEAST(v_n, 90);
END $$;

-- ── Il ricalcolo ─────────────────────────────────────────────────────────
-- ⚠️ La stessa regola (prima pesata del giorno, stima col trend, buco massimo 14 giorni) va
-- spiegata uguale nella pagina: la pagina non la ricalcola, legge la tabella.
CREATE OR REPLACE FUNCTION public.ps_ema_ricalcola(p_user uuid DEFAULT NULL)
RETURNS jsonb
LANGUAGE plpgsql VOLATILE SECURITY INVOKER SET search_path = public
AS $$
DECLARE
  MAX_BUCO constant int := 14;
  v_user uuid := COALESCE(p_user, auth.uid());
  v_n int := ps_ema_giorni();
  a numeric;
  v_d0 date; v_d1 date; L int;
  -- valori veri, per offset di giorno (1 = v_d0)
  xw numeric[]; xp numeric[]; xk numeric[];
  -- prossimo giorno vero, per serie
  nw int[]; np int[]; nk int[];
  gd date[]; gw numeric[]; gp numeric[]; gk numeric[];
  i int; j int; g date;
  -- stato delle tre serie: media, trend, avviata
  ew numeric; tw numeric; sw boolean := false;
  ep numeric; tp numeric; sp boolean := false;
  ek numeric; tk numeric; sk boolean := false;
  vw numeric; vp numeric; vk numeric;
  stw boolean; stf boolean;
  e_new numeric;
  v_righe int := 0;
BEGIN
  IF v_user IS NULL THEN
    RETURN jsonb_build_object('ok', false, 'error', 'Utente sconosciuto.');
  END IF;
  a := 2.0 / (v_n + 1);

  DELETE FROM ps_daily_ema WHERE user_id = v_user;

  -- Le pesate dell'utente. La colonna user_id su ps_weight_tracking può non esserci (la tabella
  -- è nata a mano): si legge da to_jsonb, e senza colonna valgono le righe che la RLS lascia.
  SELECT array_agg(d ORDER BY d), array_agg(w ORDER BY d), array_agg(p ORDER BY d), array_agg(k ORDER BY d)
    INTO gd, gw, gp, gk
    FROM (
  SELECT d, max(w) AS w, max(p) AS p, max(k) AS k FROM (
    -- Il peso del giorno: la PRIMA pesata della giornata.
    (SELECT DISTINCT ON (d) d, w, NULL::numeric AS p, NULL::numeric AS k
      FROM (SELECT (r->>'date')::date d, NULLIF(r->>'weight', '')::numeric w,
                   COALESCE(NULLIF(r->>'timestamp', '')::numeric, 0) ts
              FROM ps_weight_tracking t, LATERAL to_jsonb(t) r
             WHERE NULLIF(r->>'weight', '') IS NOT NULL AND NULLIF(r->>'date', '') IS NOT NULL
               AND (r->>'user_id' IS NULL OR r->>'user_id' = v_user::text)) s
     ORDER BY d, ts)
    UNION ALL
    -- Il grasso del giorno: la prima pesata che ce l'ha; i kg con il peso di quella stessa pesata.
    (SELECT DISTINCT ON (d) d, NULL::numeric, p, round(w * p / 100, 3)
      FROM (SELECT (r->>'date')::date d, NULLIF(r->>'weight', '')::numeric w,
                   NULLIF(r->>'body_fat_pct', '')::numeric p,
                   COALESCE(NULLIF(r->>'timestamp', '')::numeric, 0) ts
              FROM ps_weight_tracking t, LATERAL to_jsonb(t) r
             WHERE NULLIF(r->>'weight', '') IS NOT NULL AND NULLIF(r->>'date', '') IS NOT NULL
               AND NULLIF(r->>'body_fat_pct', '') IS NOT NULL
               AND (r->>'user_id' IS NULL OR r->>'user_id' = v_user::text)) s
     WHERE p > 0 AND p < 100
     ORDER BY d, ts)
  ) u GROUP BY d) q;

  IF gd IS NULL OR array_length(gd, 1) IS NULL THEN
    RETURN jsonb_build_object('ok', true, 'righe', 0, 'giorni', v_n);
  END IF;
  v_d0 := gd[1];
  v_d1 := gd[array_length(gd, 1)];
  L := (v_d1 - v_d0) + 1;
  xw := array_fill(NULL::numeric, ARRAY[L]); xp := xw; xk := xw;
  nw := array_fill(NULL::int, ARRAY[L]); np := nw; nk := nw;
  FOR j IN 1 .. array_length(gd, 1) LOOP
    i := (gd[j] - v_d0) + 1;
    xw[i] := gw[j]; xp[i] := gp[j]; xk[i] := gk[j];
  END LOOP;
  -- Il prossimo giorno vero di ogni serie, da destra a sinistra.
  FOR i IN REVERSE L .. 1 LOOP
    IF i < L THEN nw[i] := nw[i + 1]; np[i] := np[i + 1]; nk[i] := nk[i + 1]; END IF;
    IF xw[i] IS NOT NULL THEN nw[i] := i; END IF;
    IF xp[i] IS NOT NULL THEN np[i] := i; END IF;
    IF xk[i] IS NOT NULL THEN nk[i] := i; END IF;
  END LOOP;

  FOR i IN 1 .. L LOOP
    g := v_d0 + (i - 1);
    vw := NULL; vp := NULL; vk := NULL; stw := false; stf := false;

    -- ── peso ──
    IF xw[i] IS NOT NULL THEN
      vw := xw[i];
      IF NOT sw THEN ew := vw; tw := 0; sw := true;
      ELSE e_new := a * vw + (1 - a) * ew; tw := a * (e_new - ew) + (1 - a) * tw; ew := e_new; END IF;
    ELSIF sw AND nw[i] IS NOT NULL AND nw[i] - i < MAX_BUCO THEN
      vw := ew + tw; stw := true;
      e_new := a * vw + (1 - a) * ew; tw := a * (e_new - ew) + (1 - a) * tw; ew := e_new;
    ELSE
      sw := false; ew := NULL; tw := NULL;
    END IF;

    -- ── % di grasso ──
    IF xp[i] IS NOT NULL THEN
      vp := xp[i];
      IF NOT sp THEN ep := vp; tp := 0; sp := true;
      ELSE e_new := a * vp + (1 - a) * ep; tp := a * (e_new - ep) + (1 - a) * tp; ep := e_new; END IF;
    ELSIF sp AND np[i] IS NOT NULL AND np[i] - i < MAX_BUCO THEN
      vp := ep + tp; stf := true;
      e_new := a * vp + (1 - a) * ep; tp := a * (e_new - ep) + (1 - a) * tp; ep := e_new;
    ELSE
      sp := false; ep := NULL; tp := NULL;
    END IF;

    -- ── massa grassa (kg) ──
    IF xk[i] IS NOT NULL THEN
      vk := xk[i];
      IF NOT sk THEN ek := vk; tk := 0; sk := true;
      ELSE e_new := a * vk + (1 - a) * ek; tk := a * (e_new - ek) + (1 - a) * tk; ek := e_new; END IF;
    ELSIF sk AND nk[i] IS NOT NULL AND nk[i] - i < MAX_BUCO THEN
      vk := ek + tk; stf := true;
      e_new := a * vk + (1 - a) * ek; tk := a * (e_new - ek) + (1 - a) * tk; ek := e_new;
    ELSE
      sk := false; ek := NULL; tk := NULL;
    END IF;

    IF vw IS NOT NULL OR vp IS NOT NULL OR vk IS NOT NULL THEN
      INSERT INTO ps_daily_ema (user_id, day, weight, fat_pct, fat_kg, weight_stimato, fat_stimato,
                                ema_weight, ema_fat_pct, ema_fat_kg,
                                trend_weight, trend_fat_pct, trend_fat_kg, ema_days)
      VALUES (v_user, g, round(vw, 2), round(vp, 2), round(vk, 2), stw, stf,
              round(ew, 3), round(ep, 3), round(ek, 3),
              round(tw, 4), round(tp, 4), round(tk, 4), v_n);
      v_righe := v_righe + 1;
    END IF;
  END LOOP;

  RETURN jsonb_build_object('ok', true, 'righe', v_righe, 'giorni', v_n);
END $$;

-- ── Il trigger: ogni scrittura sulle pesate rifà le medie ─────────────────
-- Per istruzione e non per riga: una sincronizzazione scrive centinaia di righe in un colpo.
-- ⚠️ Un errore qui NON deve far fallire la pesata: si scrive un avviso e basta.
CREATE OR REPLACE FUNCTION public.ps_ema_trigger()
RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path = public
AS $$
BEGIN
  IF auth.uid() IS NOT NULL THEN
    BEGIN
      PERFORM ps_ema_ricalcola(auth.uid());
    EXCEPTION WHEN OTHERS THEN
      RAISE WARNING 'ps_ema_ricalcola: %', SQLERRM;
    END;
  END IF;
  RETURN NULL;
END $$;

DROP TRIGGER IF EXISTS trg_ps_weight_tracking_ema ON public.ps_weight_tracking;
CREATE TRIGGER trg_ps_weight_tracking_ema
  AFTER INSERT OR UPDATE OR DELETE ON public.ps_weight_tracking
  FOR EACH STATEMENT EXECUTE FUNCTION public.ps_ema_trigger();

-- ── I punti ──────────────────────────────────────────────────────────────
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
  v_grasso := COALESCE((o->>'use_fat_mass')::boolean, false);
  v_magra := CASE WHEN v_grasso THEN ps_massa_magra(o) END;
  IF v_magra IS NULL THEN v_grasso := false; END IF;
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

  -- La media mobile del periodo, giorno per giorno.
  IF v_grasso AND v_inizio IS NOT NULL THEN
    SELECT COALESCE(jsonb_object_agg(day::text, jsonb_build_object(
             'ema', ema_fat_kg, 'stimato', fat_stimato, 'peso', weight, 'peso_stimato', weight_stimato)), '{}'::jsonb)
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
        IF v_grasso THEN
          -- ⚠️ Sulla massa grassa conta la MEDIA MOBILE del giorno, vera o stimata col trend.
          v_tgt := ps_grasso_previsto(COALESCE(CASE WHEN i IS NOT NULL THEN v_t[i] END, ps_peso_piano_al(ms, g)), v_magra);
          e := v_ema->(g::text);
          v_peso := NULLIF(e->>'ema', '')::numeric;
          IF v_peso IS NOT NULL AND v_tgt IS NOT NULL THEN
            v_pti := CASE WHEN round(v_peso, 1) <= round(v_tgt, 1) THEN v_bonus ELSE -v_malus END;
            v_somma := v_somma + v_pti;
            v_rows := v_rows || jsonb_build_object('date', g,
              'interp', COALESCE((e->>'stimato')::boolean, false), 'missed', false,
              'weight', round(v_peso, 2), 'target', v_tgt, 'points', v_pti, 'cum', v_somma, 'ema', true);
          ELSIF v_peso IS NULL AND g < p_oggi THEN
            -- Nessun valore, né vero né stimato: è come non essersi pesati. Oggi no, non è finito.
            v_somma := v_somma - v_malus;
            v_rows := v_rows || jsonb_build_object('date', g, 'interp', false, 'missed', true,
              'weight', NULL, 'target', v_tgt, 'points', -v_malus, 'cum', v_somma, 'ema', true);
          END IF;
        ELSIF i IS NOT NULL THEN
          v_tgt := COALESCE(v_t[i], ps_peso_piano_al(ms, g));
          v_peso := v_m[i];
          IF v_tgt IS NOT NULL AND v_peso IS NOT NULL THEN
            v_pti := CASE WHEN round(v_peso, 1) <= round(v_tgt, 1) THEN v_bonus ELSE -v_malus END;
            v_somma := v_somma + v_pti;
            v_rows := v_rows || jsonb_build_object('date', g, 'interp', false, 'missed', false,
              'weight', v_peso, 'target', v_tgt, 'points', v_pti, 'cum', v_somma);
          END IF;
        ELSIF v_ogni IS NOT NULL THEN
          IF g < p_oggi THEN
            v_tgt := ps_peso_piano_al(ms, g);
            v_somma := v_somma - v_malus;
            v_rows := v_rows || jsonb_build_object('date', g, 'interp', false, 'missed', true,
              'weight', NULL, 'target', v_tgt, 'points', -v_malus, 'cum', v_somma);
          END IF;
        ELSE
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
    'ema', v_grasso,
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

  v_magra := CASE WHEN COALESCE((o->>'use_fat_mass')::boolean, false) THEN ps_massa_magra(o) END;
  v_grasso := v_magra IS NOT NULL;
  v_cosa := CASE WHEN v_grasso THEN ' della media della massa grassa' ELSE '' END;
  v_nessuna := CASE WHEN v_grasso THEN 'nessuna media della massa grassa' ELSE 'nessun peso registrato' END;

  IF p_stato = 'success' THEN
    v_ew := NULLIF(o->>'end_weight', '')::numeric;
    IF v_ew IS NULL THEN
      RETURN jsonb_build_object('ok', false,
        'error', 'Obiettivo senza peso target valido: impossibile chiudere con SUCCESSO.');
    END IF;
    IF v_grasso THEN
      v_ew := ps_grasso_previsto(v_ew, v_magra);
    END IF;
    v_inizio := NULLIF(o->>'start_date', '')::date;
    v_fine   := NULLIF(o->>'end_date', '')::date;
    IF o->>'objective_type' = 'mantenere' THEN
      IF v_grasso THEN
        SELECT max(ema_fat_kg) INTO v_val FROM ps_daily_ema
         WHERE user_id = auth.uid() AND day >= v_inizio AND day <= v_fine;
      ELSE
        SELECT max(ps_peso_conta(r, false)) INTO v_val
          FROM ps_weight_tracking w, LATERAL to_jsonb(w) r
         WHERE r->>'weight' IS NOT NULL
           AND (r->>'date')::date >= v_inizio AND (r->>'date')::date <= v_fine;
      END IF;
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
      IF v_grasso THEN
        SELECT ema_fat_kg INTO v_val FROM ps_daily_ema
         WHERE user_id = auth.uid() AND day = p_oggi AND NOT fat_stimato;
      ELSE
        SELECT min(ps_peso_conta(r, false)) INTO v_val
          FROM ps_weight_tracking w, LATERAL to_jsonb(w) r
         WHERE r->>'weight' IS NOT NULL AND (r->>'date')::date = p_oggi;
      END IF;
      IF v_val IS NULL THEN
        RETURN jsonb_build_object('ok', false, 'error',
          format('Non puoi chiudere con SUCCESSO: %s oggi (%s).',
                 CASE WHEN v_grasso THEN 'nessuna pesata col grasso corporeo' ELSE 'nessun peso registrato' END,
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

REVOKE ALL ON FUNCTION public.ps_ema_ricalcola(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.ps_ema_giorni() TO authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.ps_ema_ricalcola(uuid) TO authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.ps_punti(text, date) TO authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.ps_chiudi_obiettivo(text, text, date, boolean) TO authenticated, service_role;

-- ── Il primo riempimento ─────────────────────────────────────────────────
-- La migration gira senza utente: si fa per il proprietario dei dati (Salvatore). Senza di lui
-- (progetto dev) si salta senza fallire; il trigger la riempirà alla prima pesata.
DO $$
DECLARE v_u uuid;
BEGIN
  BEGIN
    v_u := public.garsal_user_id();
  EXCEPTION WHEN OTHERS THEN v_u := NULL;
  END;
  IF v_u IS NOT NULL THEN
    PERFORM public.ps_ema_ricalcola(v_u);
  ELSE
    RAISE NOTICE 'ps_daily_ema: nessun utente, primo riempimento saltato';
  END IF;
END $$;
