-- Le notifiche delle fasce di studio di Ada (Edge Function sc-promemoria).
--
-- 1. cm_notification_rules ammette app = 'scuola': le righe della coda vogliono
--    un rule_id, e sc-promemoria se ne crea una per utente e canale, SPENTA
--    (enabled = false), così fill-notification-queue non la legge.
-- 2. sc_promemoria_inviati: una riga per notifica mandata (giorno, fascia, tipo),
--    scritta PRIMA di mandare: la stessa notifica non parte due volte.
--    Solo service role: RLS accesa e nessuna policy.
-- 3. Il job pg_cron ogni 5 minuti.

ALTER TABLE public.cm_notification_rules DROP CONSTRAINT IF EXISTS cm_notification_rules_app_check;
ALTER TABLE public.cm_notification_rules ADD CONSTRAINT cm_notification_rules_app_check
  CHECK (app = ANY (ARRAY['cost_analysis','events','habits','objectives','plants','quick','scuola','ta_firi','tasks','weight']));

CREATE TABLE IF NOT EXISTS public.sc_promemoria_inviati (
  giorno     date        NOT NULL,
  fascia     text        NOT NULL,          -- '15:00-17:00'
  tipo       text        NOT NULL CHECK (tipo IN ('inizio', 'fine')),
  inviato_il timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (giorno, fascia, tipo)
);
ALTER TABLE public.sc_promemoria_inviati ENABLE ROW LEVEL SECURITY;

DO $$
BEGIN
  PERFORM cron.unschedule('sc-promemoria') WHERE EXISTS (SELECT 1 FROM cron.job WHERE jobname = 'sc-promemoria');
END $$;

SELECT cron.schedule(
  'sc-promemoria',
  '*/5 * * * *',
  $$
  select net.http_post(
    url     := 'https://jajlmmdsjlvzgcxiiypk.supabase.co/functions/v1/sc-promemoria',
    headers := jsonb_build_object(
      'Content-Type', 'application/json',
      'Authorization', 'Bearer ' || (
        select decrypted_secret from vault.decrypted_secrets where name = 'supabase_service_role_key'
      )
    ),
    body := '{}'::jsonb
  );
  $$
);
