-- «Ti pisasti?»: la media mobile diventa SEMPLICE (web v4.15.5).
--
-- Fino a qui era esponenziale (α = 2 / (N + 1)), coi giorni senza pesata stimati col trend.
-- Ora la media di un giorno è la media aritmetica delle pesate VERE degli ultimi N giorni di
-- calendario (quel giorno compreso): ognuna pesa uguale, ed esce dal conto dopo N giorni.
--
--   • il valore di un giorno resta la PRIMA pesata della giornata, una serie per peso, % di
--     grasso e massa grassa (kg di quella stessa pesata);
--   • un giorno senza pesata NON si stima più: la sua media è quella delle pesate vere che
--     cadono nella finestra. Senza nessuna pesata negli N giorni la media non c'è;
--   • la riga di un giorno senza pesata resta marcata «stimata» e nel valore porta la media
--     (com'era la stima prima): pagina, nativo e ps_punti la leggono come prima;
--   • il trend è media di oggi − media di ieri (NULL se ieri non c'era);
--   • il buco massimo di 14 giorni non serve più: la finestra di N giorni lo fa da sé.
--
-- ⚠️ Le colonne restano ema_* di proposito: cambia il calcolo, non la tabella, quindi web,
-- APK nativa e le RPC ps_punti / ps_chiudi_obiettivo leggono le stesse colonne di prima.
-- ⚠️ Cambiano i punti degli obiettivi sulla massa grassa ancora aperti (li calcola ps_punti
-- dal vivo sulla media). Il total_score degli obiettivi già chiusi è congelato e non si tocca.

CREATE OR REPLACE FUNCTION public.ps_ema_ricalcola(p_user uuid DEFAULT NULL)
RETURNS jsonb
LANGUAGE plpgsql VOLATILE SECURITY INVOKER SET search_path = public
AS $$
DECLARE
  v_user uuid := COALESCE(p_user, auth.uid());
  v_n int := ps_ema_giorni();
  v_d0 date; v_d1 date; L int;
  -- valori veri, per offset di giorno (1 = v_d0)
  xw numeric[]; xp numeric[]; xk numeric[];
  gd date[]; gw numeric[]; gp numeric[]; gk numeric[];
  i int; j int; g date;
  -- somme e conteggi della finestra, per serie
  sw numeric := 0; cw int := 0;
  sp numeric := 0; cp int := 0;
  sk numeric := 0; ck int := 0;
  mw numeric; mp numeric; mk numeric;
  pw numeric; pp numeric; pk numeric;  -- le medie di ieri, per il trend
  vw numeric; vp numeric; vk numeric;
  stw boolean; stf boolean;
  v_righe int := 0;
BEGIN
  IF v_user IS NULL THEN
    RETURN jsonb_build_object('ok', false, 'error', 'Utente sconosciuto.');
  END IF;

  DELETE FROM ps_daily_ema WHERE user_id = v_user;

  -- Le pesate dell'utente: la stessa lettura di prima (prima pesata del giorno; il grasso dalla
  -- prima pesata che ce l'ha, coi kg calcolati sul peso di quella stessa pesata).
  SELECT array_agg(d ORDER BY d), array_agg(w ORDER BY d), array_agg(p ORDER BY d), array_agg(k ORDER BY d)
    INTO gd, gw, gp, gk
    FROM (
  SELECT d, max(w) AS w, max(p) AS p, max(k) AS k FROM (
    (SELECT DISTINCT ON (d) d, w, NULL::numeric AS p, NULL::numeric AS k
      FROM (SELECT (r->>'date')::date d, NULLIF(r->>'weight', '')::numeric w,
                   COALESCE(NULLIF(r->>'timestamp', '')::numeric, 0) ts
              FROM ps_weight_tracking t, LATERAL to_jsonb(t) r
             WHERE NULLIF(r->>'weight', '') IS NOT NULL AND NULLIF(r->>'date', '') IS NOT NULL
               AND (r->>'user_id' IS NULL OR r->>'user_id' = v_user::text)) s
     ORDER BY d, ts)
    UNION ALL
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
  FOR j IN 1 .. array_length(gd, 1) LOOP
    i := (gd[j] - v_d0) + 1;
    xw[i] := gw[j]; xp[i] := gp[j]; xk[i] := gk[j];
  END LOOP;

  FOR i IN 1 .. L LOOP
    g := v_d0 + (i - 1);

    -- Entra il giorno i, esce il giorno i − N.
    IF xw[i] IS NOT NULL THEN sw := sw + xw[i]; cw := cw + 1; END IF;
    IF xp[i] IS NOT NULL THEN sp := sp + xp[i]; cp := cp + 1; END IF;
    IF xk[i] IS NOT NULL THEN sk := sk + xk[i]; ck := ck + 1; END IF;
    IF i > v_n THEN
      IF xw[i - v_n] IS NOT NULL THEN sw := sw - xw[i - v_n]; cw := cw - 1; END IF;
      IF xp[i - v_n] IS NOT NULL THEN sp := sp - xp[i - v_n]; cp := cp - 1; END IF;
      IF xk[i - v_n] IS NOT NULL THEN sk := sk - xk[i - v_n]; ck := ck - 1; END IF;
    END IF;

    mw := CASE WHEN cw > 0 THEN sw / cw END;
    mp := CASE WHEN cp > 0 THEN sp / cp END;
    mk := CASE WHEN ck > 0 THEN sk / ck END;

    -- Il valore del giorno: la pesata vera, o (senza) la media, marcata stimata.
    vw := COALESCE(xw[i], mw); stw := xw[i] IS NULL AND mw IS NOT NULL;
    vp := COALESCE(xp[i], mp);
    vk := COALESCE(xk[i], mk);
    stf := (xp[i] IS NULL AND mp IS NOT NULL) OR (xk[i] IS NULL AND mk IS NOT NULL);

    IF mw IS NOT NULL OR mp IS NOT NULL OR mk IS NOT NULL THEN
      INSERT INTO ps_daily_ema (user_id, day, weight, fat_pct, fat_kg, weight_stimato, fat_stimato,
                                ema_weight, ema_fat_pct, ema_fat_kg,
                                trend_weight, trend_fat_pct, trend_fat_kg, ema_days)
      VALUES (v_user, g, round(vw, 2), round(vp, 2), round(vk, 2), stw, stf,
              round(mw, 3), round(mp, 3), round(mk, 3),
              round(mw - pw, 4), round(mp - pp, 4), round(mk - pk, 4), v_n);
      v_righe := v_righe + 1;
    END IF;
    pw := mw; pp := mp; pk := mk;
  END LOOP;

  RETURN jsonb_build_object('ok', true, 'righe', v_righe, 'giorni', v_n, 'tipo', 'semplice');
END $$;

REVOKE ALL ON FUNCTION public.ps_ema_ricalcola(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.ps_ema_ricalcola(uuid) TO authenticated, service_role;

-- Il ricalcolo per il proprietario dei dati; senza (progetto dev) si salta.
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
    RAISE NOTICE 'ps_daily_ema: nessun utente, ricalcolo saltato';
  END IF;
END $$;
