-- Scarta le pesate SINCRONIZZATE (Health Connect / Renpho) che si scostano di oltre il 10 %
-- dalla media mobile del giorno prima. Nasce da due pesate da 46,5 kg finite in archivio con
-- una media di 80: la bilancia le aveva scritte in Health Connect, e ogni sincronizzazione
-- (che rilegge 90 giorni) le rimetteva dentro anche dopo averle cancellate.
--
-- ⚠️ Nel database e non nelle app: le sincronizzazioni sono tre (weight-quest.html, APK
-- WebView, APK nativa) e un filtro solo le copre tutte.
-- ⚠️ Solo sincronizzazione: una pesata scritta a mano (`time = 'Manuale'`) passa sempre.
-- ⚠️ Solo INSERT: correggere una pesata già in archivio non viene bloccato. Una riga già
--    presente che torna con l'upsert (on_conflict=timestamp) passa comunque dal BEFORE INSERT,
--    quindi viene scartata anche lei e la riga esistente resta com'è.
-- ⚠️ Il metro è `ps_daily_ema.ema_weight` dell'ultimo giorno PRIMA della pesata, entro 30
--    giorni; senza media si ripiega sull'ultima pesata precedente, entro 30 giorni. Senza
--    nessun riferimento (prima pesata, o un buco di oltre un mese) la pesata passa: un
--    cambiamento vero dopo un mese di assenza non è un errore della bilancia.
-- ⚠️ La riga scartata sparisce in silenzio (RETURN NULL): far fallire l'insert farebbe
--    fallire l'intero upsert, cioè tutta la sincronizzazione per una pesata sola.
-- ⚠️ user_id su ps_weight_tracking può non esserci (tabella nata a mano): si legge da
--    to_jsonb, come in ps_ema_ricalcola.

CREATE OR REPLACE FUNCTION public.ps_filtro_pesata_anomala()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
DECLARE
  v_riga   jsonb := to_jsonb(NEW);
  v_user   uuid;
  v_giorno date;
  v_peso   numeric;
  v_rif    numeric;
BEGIN
  IF COALESCE(v_riga->>'time', '') = 'Manuale' THEN
    RETURN NEW;
  END IF;

  v_peso := NULLIF(v_riga->>'weight', '')::numeric;
  IF v_peso IS NULL OR v_peso <= 0 THEN
    RETURN NEW;
  END IF;

  v_user := COALESCE(NULLIF(v_riga->>'user_id', '')::uuid, auth.uid());
  BEGIN
    v_giorno := COALESCE(
      NULLIF(v_riga->>'date', '')::date,
      (to_timestamp((v_riga->>'timestamp')::bigint / 1000.0) AT TIME ZONE 'Europe/Rome')::date
    );
  EXCEPTION WHEN OTHERS THEN
    RETURN NEW;
  END;
  IF v_user IS NULL OR v_giorno IS NULL THEN
    RETURN NEW;
  END IF;

  SELECT e.ema_weight INTO v_rif
    FROM ps_daily_ema e
   WHERE e.user_id = v_user
     AND e.day < v_giorno
     AND e.day >= v_giorno - 30
     AND e.ema_weight IS NOT NULL
   ORDER BY e.day DESC
   LIMIT 1;

  IF v_rif IS NULL THEN
    SELECT NULLIF(to_jsonb(t)->>'weight', '')::numeric INTO v_rif
      FROM ps_weight_tracking t
     WHERE to_jsonb(t)->>'user_id' = v_user::text
       AND (to_jsonb(t)->>'timestamp')::bigint < (v_riga->>'timestamp')::bigint
       AND (to_jsonb(t)->>'timestamp')::bigint >= (v_riga->>'timestamp')::bigint - 30::bigint * 86400000
     ORDER BY (to_jsonb(t)->>'timestamp')::bigint DESC
     LIMIT 1;
  END IF;

  IF v_rif IS NULL OR v_rif <= 0 THEN
    RETURN NEW;
  END IF;

  IF abs(v_peso - v_rif) / v_rif > 0.10 THEN
    RAISE NOTICE 'ps_filtro_pesata_anomala: scartata % kg del % (riferimento % kg)', v_peso, v_giorno, round(v_rif, 2);
    RETURN NULL;
  END IF;

  RETURN NEW;
END $function$;

DROP TRIGGER IF EXISTS trg_ps_weight_tracking_filtro ON public.ps_weight_tracking;
CREATE TRIGGER trg_ps_weight_tracking_filtro
  BEFORE INSERT ON public.ps_weight_tracking
  FOR EACH ROW EXECUTE FUNCTION public.ps_filtro_pesata_anomala();
