-- Snapshot automatico serale del patrimonio (Finanza).
--
-- Finora fnz_dashboard_snapshots veniva scritto solo da finanza.html all'apertura
-- dell'app (autoSaveSnapshot): nei giorni in cui l'app non veniva aperta lo storico
-- restava senza riga, e la variazione "da ieri" confrontava date lontane fra loro.
-- La Edge Function save-snapshot fa la stessa cosa lato server, per ogni utente che
-- ha dati di Finanza, dopo aver aggiornato i prezzi chiamando get-prices.
--
-- Schedule '0 21 * * *' in UTC = 23:00 CEST (UTC+2, ora legale).
-- Quando torna l'ora solare (UTC+1, fine ottobre) va aggiornato a '0 22 * * *' per
-- restare alle 23:00 locali — pg_cron non gestisce da solo il cambio ora, stessa
-- avvertenza del job revolut-auto-categorize.
--
-- Nota: se durante la giornata l'utente ha già aperto Finanza, esiste già una riga
-- per oggi; l'upsert su (user_id, snapshot_date) la sovrascrive con i valori delle
-- 23:00, che sono quelli a mercati chiusi e quindi i più rappresentativi della
-- giornata. Non vengono create righe doppie.

select cron.unschedule('fnz-save-snapshot')
where exists (select 1 from cron.job where jobname = 'fnz-save-snapshot');

select cron.schedule(
  'fnz-save-snapshot',
  '0 21 * * *',
  $$
  select net.http_post(
    url     := 'https://jajlmmdsjlvzgcxiiypk.supabase.co/functions/v1/save-snapshot',
    headers := jsonb_build_object(
      'Content-Type', 'application/json',
      'Authorization', 'Bearer ' || (
        select decrypted_secret from vault.decrypted_secrets where name = 'supabase_service_role_key'
      )
    ),
    body    := '{}'::jsonb,
    timeout_milliseconds := 600000
  );
  $$
);
