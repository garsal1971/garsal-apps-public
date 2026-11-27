-- Un giorno senza pesata, se sta FRA DUE PESATE VERE, riceve una riga vera in
-- ps_weight_tracking col peso interpolato sulla retta fra le due (time = 'Interp.').
-- Giorno x 80 kg, x+1 niente, x+2 79 kg  →  x+1 79,5 kg. Buchi di qualunque lunghezza.
--
-- ⚠️ Le righe interpolate si RICAVANO dalle vere e si riallineano da sole a ogni scrittura su
--    ps_weight_tracking (trigger per istruzione): una pesata vera che arriva in un giorno
--    interpolato la toglie, e cambiando una vicina cambia il valore. Si toccano solo le righe
--    che non tornano (stesso giorno, stesso peso restano), così gli id non girano.
-- ⚠️ Oggi senza pesata non si interpola: manca la pesata «dopo». Domani, pesandosi, sì.
-- ⚠️ Contano come pesate: entrano nella media mobile (ps_ema_ricalcola le legge come le
--    altre), quindi i punti di ps_punti si rifanno da soli. target_weight resta NULL: ps_punti
--    ripiega sul target del piano di quel giorno.
-- ⚠️ Il filtro delle pesate anomale le lascia passare.
-- ⚠️ timestamp = mezzogiorno di Roma di quel giorno, in ms: in quel giorno non c'è nessuna
--    pesata vera, quindi non si scontra con l'upsert on_conflict=timestamp delle sync.

CREATE OR REPLACE FUNCTION public.ps_interpola_pesate(p_user uuid DEFAULT NULL::uuid)
RETURNS jsonb
LANGUAGE plpgsql
SET search_path TO 'public'
AS $function$
DECLARE
  v_user uuid := COALESCE(p_user, auth.uid());
  v_tolte int := 0; v_nuove int := 0;
BEGIN
  IF v_user IS NULL THEN
    RETURN jsonb_build_object('ok', false, 'error', 'Utente sconosciuto.');
  END IF;
  IF current_setting('ps.interpola_in_corso', true) = '1' THEN
    RETURN jsonb_build_object('ok', true, 'saltato', true);
  END IF;
  PERFORM set_config('ps.interpola_in_corso', '1', true);

  CREATE TEMP TABLE IF NOT EXISTS _ps_interp (d date PRIMARY KEY, w numeric) ON COMMIT DROP;
  TRUNCATE _ps_interp;

  -- Le giornate vere (l'ultima pesata per orario), e per ognuna la successiva.
  INSERT INTO _ps_interp (d, w)
  SELECT g::date,
         round(q.w + (q.wn - q.w) * ((g::date - q.d)::numeric / (q.dn - q.d)), 2)
    FROM (
      SELECT d, w, lead(d) OVER (ORDER BY d) dn, lead(w) OVER (ORDER BY d) wn
        FROM (SELECT DISTINCT ON (d) d, w
                FROM (SELECT (r->>'date')::date d, NULLIF(r->>'weight', '')::numeric w,
                             COALESCE(NULLIF(r->>'timestamp', '')::numeric, 0) ts
                        FROM ps_weight_tracking t, LATERAL to_jsonb(t) r
                       WHERE NULLIF(r->>'weight', '') IS NOT NULL AND NULLIF(r->>'date', '') IS NOT NULL
                         AND COALESCE(r->>'time', '') <> 'Interp.'
                         AND (r->>'user_id' IS NULL OR r->>'user_id' = v_user::text)) s
               ORDER BY d, ts DESC) v
    ) q,
    LATERAL generate_series(q.d + 1, q.dn - 1, interval '1 day') g
   WHERE q.dn IS NOT NULL AND q.dn - q.d > 1;

  DELETE FROM ps_weight_tracking t
   WHERE t.user_id = v_user AND t.time = 'Interp.'
     AND NOT EXISTS (SELECT 1 FROM _ps_interp i WHERE i.d = t.date AND i.w = t.weight);
  GET DIAGNOSTICS v_tolte = ROW_COUNT;

  INSERT INTO ps_weight_tracking (date, time, "timestamp", weight, target_weight, user_id)
  SELECT i.d, 'Interp.',
         (extract(epoch FROM ((i.d + time '12:00') AT TIME ZONE 'Europe/Rome')) * 1000)::bigint,
         i.w, NULL, v_user
    FROM _ps_interp i
   WHERE NOT EXISTS (SELECT 1 FROM ps_weight_tracking t
                      WHERE t.user_id = v_user AND t.time = 'Interp.' AND t.date = i.d);
  GET DIAGNOSTICS v_nuove = ROW_COUNT;

  PERFORM set_config('ps.interpola_in_corso', '0', true);
  RETURN jsonb_build_object('ok', true, 'tolte', v_tolte, 'nuove', v_nuove);
END $function$;

-- Il trigger per istruzione: prima le interpolate, poi la media mobile. Le scritture fatte
-- da ps_interpola_pesate fanno ripartire il trigger, che lì esce subito.
CREATE OR REPLACE FUNCTION public.ps_ema_trigger()
 RETURNS trigger
 LANGUAGE plpgsql
 SET search_path TO 'public'
AS $function$
BEGIN
  IF current_setting('ps.interpola_in_corso', true) = '1' THEN
    RETURN NULL;
  END IF;
  IF auth.uid() IS NOT NULL THEN
    BEGIN
      PERFORM ps_interpola_pesate(auth.uid());
      PERFORM ps_ema_ricalcola(auth.uid());
    EXCEPTION WHEN OTHERS THEN
      RAISE WARNING 'ps_ema_trigger: %', SQLERRM;
    END;
  END IF;
  RETURN NULL;
END $function$;

-- Il filtro delle pesate anomale lascia passare le interpolate (stanno fra due pesate vere).
DO $$
DECLARE def text;
BEGIN
  def := pg_get_functiondef('ps_filtro_pesata_anomala()'::regprocedure);
  IF position($a$IF COALESCE(v_riga->>'time', '') = 'Manuale' THEN$a$ IN def) = 0 THEN
    RAISE EXCEPTION 'Non trovo il controllo Manuale in ps_filtro_pesata_anomala';
  END IF;
  EXECUTE replace(def, $a$IF COALESCE(v_riga->>'time', '') = 'Manuale' THEN$a$,
                       $b$IF COALESCE(v_riga->>'time', '') IN ('Manuale', 'Interp.') THEN$b$);
END $$;

-- Prima passata per ogni utente con pesate, poi la media, poi il punteggio degli obiettivi
-- CHIUSI rifatto con le regole di oggi (richiesta esplicita). La data di chiusura non è
-- archiviata (updated_at è quella di creazione): si conta fino alla fine prevista, ma non
-- oltre il giorno prima dell'inizio dell'obiettivo successivo — si chiudeva per aprirne un
-- altro — e non oltre oggi.
DO $$
DECLARE
  u uuid; o record; p jsonb; v_fino date; v_dopo date; v_tot int;
BEGIN
  FOR u IN SELECT DISTINCT user_id FROM ps_weight_tracking WHERE user_id IS NOT NULL LOOP
    PERFORM set_config('request.jwt.claims', json_build_object('sub', u, 'role', 'authenticated')::text, true);
    PERFORM ps_interpola_pesate(u);
    PERFORM ps_ema_ricalcola(u);

    FOR o IN SELECT * FROM ps_objectives
              WHERE user_id = u AND status IN ('success', 'failed') ORDER BY start_date LOOP
      SELECT min(x.start_date) INTO v_dopo FROM ps_objectives x
       WHERE x.user_id = u AND x.start_date > o.start_date;
      v_fino := LEAST(COALESCE(o.end_date, current_date), COALESCE(v_dopo - 1, current_date), current_date);
      p := ps_punti(o.id::text, v_fino);
      IF NOT COALESCE((p->>'ok')::boolean, false) THEN CONTINUE; END IF;
      v_tot := (p->>'total')::int
             + CASE WHEN o.status = 'success'
                    THEN (p->'traguardi'->>'reached_points')::int
                         + COALESCE(NULLIF(round(o.final_bonus::numeric)::int, 0), 100)
                    ELSE -COALESCE(NULLIF(round(o.final_malus::numeric)::int, 0), 50) END;
      RAISE NOTICE 'Obiettivo % (fino al %): % → %', o.id, v_fino, o.total_score, v_tot;
      UPDATE ps_objectives SET total_score = v_tot WHERE id = o.id;
    END LOOP;
  END LOOP;
  PERFORM set_config('request.jwt.claims', '', true);
END $$;
