-- Il job pg_cron per revolut-auto-categorize non è mai esistito (verificato da Dashboard →
-- Database → Cron → Jobs: c'erano solo fill-notification-queue, send-notifications,
-- ask_for_payment) — il sync automatico Revolut non è mai partito. Lo creiamo ora con lo
-- stesso pattern net.http_post + service role key da vault già usato dagli altri job
-- (vedi screenshot job "ask_for_payment").
--
-- Schedule '0 6-20/2 * * *' in UTC = ogni 2 ore dalle 08:00 alle 22:00 CEST (UTC+2, ora legale).
-- Quando torna l'ora solare (UTC+1, fine ottobre) va aggiornato a '0 7-21/2 * * *' per restare
-- 08:00-22:00 locali — pg_cron non gestisce da solo il cambio ora.
select cron.schedule(
  'revolut-auto-categorize',
  '0 6-20/2 * * *',
  $$
  select net.http_post(
    url     := 'https://jajlmmdsjlvzgcxiiypk.supabase.co/functions/v1/revolut-auto-categorize',
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
