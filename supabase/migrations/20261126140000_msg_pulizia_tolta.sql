-- 📨 Inoltro messaggi: niente più cancellazione automatica dei messaggi oltre 90 giorni.
-- Lo storico importato dal telefono (impianto, APK 1.0.165) deve restare in msg_messaggi.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM cron.job WHERE jobname = 'msg-pulizia-90gg') THEN
    PERFORM cron.unschedule('msg-pulizia-90gg');
  END IF;
END $$;
