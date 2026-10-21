-- «Ti pisasti?»: la massa grassa esce anche dal DATABASE (web v4.17.1, APK nativa 1.0.142).
--
-- 20261021100000 l'aveva tolta dalle app lasciando le colonne, perché le APK di allora le
-- scrivevano ancora. Ora si tolgono:
--   • ps_weight_tracking.body_fat_pct;
--   • ps_objectives.use_fat_mass, ps_objectives.fat_pct;
--   • ps_daily_ema: fat_pct, fat_kg, fat_stimato, ema_fat_pct, ema_fat_kg, trend_fat_pct, trend_fat_kg.
--
-- ⚠️ Un client che manda ancora use_fat_mass / fat_pct (web ≤ v4.17.0, APK nativa ≤ 1.0.141)
-- si vede RIFIUTARE il salvataggio di un obiettivo: va aggiornato.
-- ⚠️ ps_peso_conta resta con la stessa firma (la chiama ps_punti) e torna il solo peso.

-- 1. La media mobile, del solo peso.
CREATE OR REPLACE FUNCTION public.ps_ema_ricalcola(p_user uuid DEFAULT NULL)
RETURNS jsonb
LANGUAGE plpgsql VOLATILE SECURITY INVOKER SET search_path = public
AS $$
DECLARE
  v_user uuid := COALESCE(p_user, auth.uid());
  v_n int := ps_ema_giorni();
  v_d0 date; v_d1 date; L int;
  xw numeric[];
  gd date[]; gw numeric[];
  i int; j int; g date;
  sw numeric := 0; cw int := 0;
  mw numeric; pw numeric; vw numeric;
  stw boolean;
  v_righe int := 0;
BEGIN
  IF v_user IS NULL THEN
    RETURN jsonb_build_object('ok', false, 'error', 'Utente sconosciuto.');
  END IF;

  DELETE FROM ps_daily_ema WHERE user_id = v_user;

  -- La prima pesata di ogni giorno.
  SELECT array_agg(d ORDER BY d), array_agg(w ORDER BY d)
    INTO gd, gw
    FROM (SELECT DISTINCT ON (d) d, w
            FROM (SELECT (r->>'date')::date d, NULLIF(r->>'weight', '')::numeric w,
                         COALESCE(NULLIF(r->>'timestamp', '')::numeric, 0) ts
                    FROM ps_weight_tracking t, LATERAL to_jsonb(t) r
                   WHERE NULLIF(r->>'weight', '') IS NOT NULL AND NULLIF(r->>'date', '') IS NOT NULL
                     AND (r->>'user_id' IS NULL OR r->>'user_id' = v_user::text)) s
           ORDER BY d, ts) q;

  IF gd IS NULL OR array_length(gd, 1) IS NULL THEN
    RETURN jsonb_build_object('ok', true, 'righe', 0, 'giorni', v_n);
  END IF;
  v_d0 := gd[1];
  v_d1 := gd[array_length(gd, 1)];
  L := (v_d1 - v_d0) + 1;
  xw := array_fill(NULL::numeric, ARRAY[L]);
  FOR j IN 1 .. array_length(gd, 1) LOOP
    xw[(gd[j] - v_d0) + 1] := gw[j];
  END LOOP;

  FOR i IN 1 .. L LOOP
    g := v_d0 + (i - 1);
    -- Entra il giorno i, esce il giorno i − N.
    IF xw[i] IS NOT NULL THEN sw := sw + xw[i]; cw := cw + 1; END IF;
    IF i > v_n AND xw[i - v_n] IS NOT NULL THEN sw := sw - xw[i - v_n]; cw := cw - 1; END IF;
    mw := CASE WHEN cw > 0 THEN sw / cw END;

    -- Il valore del giorno: la pesata vera, o (senza) la media, marcata stimata.
    vw := COALESCE(xw[i], mw); stw := xw[i] IS NULL AND mw IS NOT NULL;

    IF mw IS NOT NULL THEN
      INSERT INTO ps_daily_ema (user_id, day, weight, weight_stimato, ema_weight, trend_weight, ema_days)
      VALUES (v_user, g, round(vw, 2), stw, round(mw, 3), round(mw - pw, 4), v_n);
      v_righe := v_righe + 1;
    END IF;
    pw := mw;
  END LOOP;

  RETURN jsonb_build_object('ok', true, 'righe', v_righe, 'giorni', v_n, 'tipo', 'semplice');
END $$;

REVOKE ALL ON FUNCTION public.ps_ema_ricalcola(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.ps_ema_ricalcola(uuid) TO authenticated, service_role;

-- 2. ps_peso_conta: stessa firma, il solo peso.
CREATE OR REPLACE FUNCTION public.ps_peso_conta(p_riga jsonb, p_grasso boolean)
RETURNS numeric
LANGUAGE sql IMMUTABLE SET search_path = public
AS $$ SELECT NULLIF(p_riga->>'weight', '')::numeric $$;

-- 3. Le due funzioni del grasso, che non chiama più nessuno.
DROP FUNCTION IF EXISTS public.ps_massa_magra(jsonb);
DROP FUNCTION IF EXISTS public.ps_grasso_previsto(numeric, numeric);

-- 4. Le colonne.
ALTER TABLE public.ps_weight_tracking DROP COLUMN IF EXISTS body_fat_pct;
ALTER TABLE public.ps_objectives      DROP COLUMN IF EXISTS use_fat_mass,
                                      DROP COLUMN IF EXISTS fat_pct;
ALTER TABLE public.ps_daily_ema       DROP COLUMN IF EXISTS fat_pct,
                                      DROP COLUMN IF EXISTS fat_kg,
                                      DROP COLUMN IF EXISTS fat_stimato,
                                      DROP COLUMN IF EXISTS ema_fat_pct,
                                      DROP COLUMN IF EXISTS ema_fat_kg,
                                      DROP COLUMN IF EXISTS trend_fat_pct,
                                      DROP COLUMN IF EXISTS trend_fat_kg;

-- 5. Il ricalcolo per il proprietario dei dati; senza (progetto dev) si salta.
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
