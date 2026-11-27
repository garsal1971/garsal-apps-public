-- Sincronizzazione automatica di Spese Ada (Edge Function sal-sync, corpo { "conto": "ada" }).
--
-- 1. ada_transactions: i movimenti che somigliano a uno già in archivio (stesso importo entro
--    pochi giorni) entrano PROVVISORI, col riferimento a quello che somigliano. Fuori dai totali
--    finché nella pagina non si dice «Tieni» o «È un doppione».
-- 2. Il job pg_cron: il 28 di ogni mese alle 06:43 UTC.
-- La carta da importare la scrive la pagina in cm_settings, chiave 'ada_carta' ({"carta": "…"}).

ALTER TABLE public.ada_transactions
  ADD COLUMN IF NOT EXISTS provvisorio boolean NOT NULL DEFAULT false,
  ADD COLUMN IF NOT EXISTS duplicato_di uuid REFERENCES public.ada_transactions(id) ON DELETE SET NULL;

DO $$
BEGIN
  PERFORM cron.unschedule('ada-sync') WHERE EXISTS (SELECT 1 FROM cron.job WHERE jobname = 'ada-sync');
END $$;

SELECT cron.schedule(
  'ada-sync',
  '43 6 28 * *',
  $$
  select net.http_post(
    url     := 'https://jajlmmdsjlvzgcxiiypk.supabase.co/functions/v1/sal-sync',
    headers := jsonb_build_object(
      'Content-Type', 'application/json',
      'Authorization', 'Bearer ' || (
        select decrypted_secret from vault.decrypted_secrets where name = 'supabase_service_role_key'
      )
    ),
    body := '{"conto":"ada"}'::jsonb,
    timeout_milliseconds := 120000
  );
  $$
);
