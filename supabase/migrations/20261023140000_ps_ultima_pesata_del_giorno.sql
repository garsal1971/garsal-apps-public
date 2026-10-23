-- Il peso di una giornata è la sua ULTIMA pesata per orario, non più la prima (media mobile)
-- né la più bassa (punti, stelline, calorie). Una regola sola per tutto: ps_daily_ema,
-- ps_punti, al_pesi, al_peso_al. Le stesse funzioni si riscrivono dalla loro definizione
-- attuale cambiando il solo ordine, e la migration si ferma se un pezzo non si trova.

DO $$
DECLARE
  def text; nuovo text;
  f record;
BEGIN
  FOR f IN SELECT * FROM (VALUES
    ('ps_ema_ricalcola(uuid)',
     $a$           ORDER BY d, ts) q;$a$,
     $b$           ORDER BY d, ts DESC) q;$b$),
    ('ps_punti(text,date)',
     $a$         ORDER BY d, peso, ts DESC$a$,
     $b$         ORDER BY d, COALESCE(ts, 0) DESC$b$)
  ) v(fn, da, a) LOOP
    def := pg_get_functiondef(f.fn::regprocedure);
    IF position(f.da IN def) = 0 THEN
      RAISE EXCEPTION 'Non trovo il pezzo da cambiare in %', f.fn;
    END IF;
    nuovo := replace(def, f.da, f.a);
    EXECUTE nuovo;
  END LOOP;
END $$;

-- Nei commenti di ps_ema_ricalcola resta scritto «la prima pesata»: si sistema a parte.
DO $$
DECLARE def text;
BEGIN
  def := pg_get_functiondef('ps_ema_ricalcola(uuid)'::regprocedure);
  EXECUTE replace(def, '-- La prima pesata di ogni giorno.', '-- L''ultima pesata di ogni giorno (per orario).');
END $$;

-- Le pesate per il conto delle calorie: l'ultima di ogni giorno, non la più bassa.
CREATE OR REPLACE FUNCTION public.al_pesi()
 RETURNS jsonb
 LANGUAGE sql
 STABLE
 SET search_path TO 'public'
AS $function$
  SELECT COALESCE(jsonb_agg(jsonb_build_object('d', d::text, 'w', w) ORDER BY d DESC), '[]'::jsonb)
    FROM (SELECT DISTINCT ON (d) d, w
            FROM (SELECT (x->>'date')::date d, (x->>'weight')::numeric w,
                         COALESCE(NULLIF(x->>'timestamp', '')::numeric, 0) ts
                    FROM (SELECT to_jsonb(t) x FROM ps_weight_tracking t) s
                   WHERE (x->>'weight') IS NOT NULL AND (x->>'date') IS NOT NULL) q
           ORDER BY d, ts DESC) g;
$function$;

CREATE OR REPLACE FUNCTION public.al_peso_al(p_giorno date)
 RETURNS numeric
 LANGUAGE sql
 STABLE
 SET search_path TO 'public'
AS $function$
  SELECT (w->>'weight')::numeric
    FROM (SELECT to_jsonb(t) w FROM ps_weight_tracking t) x
   WHERE (w->>'weight') IS NOT NULL
     AND (w->>'date')::date <= p_giorno
   ORDER BY (w->>'date')::date DESC, COALESCE(NULLIF(w->>'timestamp', '')::numeric, 0) DESC
   LIMIT 1;
$function$;

-- Le medie già scritte si rifanno con la regola nuova, per ogni utente che ha pesate.
DO $$
DECLARE u uuid;
BEGIN
  FOR u IN SELECT DISTINCT user_id FROM ps_daily_ema WHERE user_id IS NOT NULL LOOP
    PERFORM ps_ema_ricalcola(u);
  END LOOP;
END $$;
