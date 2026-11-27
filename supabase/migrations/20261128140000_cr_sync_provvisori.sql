-- Sincronizzazione automatica del Conto Risparmio (Edge Function cr-sync).
-- 1. cntrs_transactions_terr: i movimenti con stessa data e importo di uno in archivio entrano
--    PROVVISORI, fuori dai totali e dal fondo finché nella pagina non si decide.
-- 2. Il job pg_cron: il 28 di ogni mese alle 06:23 UTC (dieci minuti dopo sal-sync).

ALTER TABLE public.cntrs_transactions_terr
  ADD COLUMN IF NOT EXISTS provvisorio boolean NOT NULL DEFAULT false,
  ADD COLUMN IF NOT EXISTS duplicato_di uuid REFERENCES public.cntrs_transactions_terr(id) ON DELETE SET NULL;

DO $$
BEGIN
  PERFORM cron.unschedule('cr-sync') WHERE EXISTS (SELECT 1 FROM cron.job WHERE jobname = 'cr-sync');
END $$;

SELECT cron.schedule(
  'cr-sync',
  '23 6 28 * *',
  $$
  select net.http_post(
    url     := 'https://jajlmmdsjlvzgcxiiypk.supabase.co/functions/v1/cr-sync',
    headers := jsonb_build_object(
      'Content-Type', 'application/json',
      'Authorization', 'Bearer ' || (
        select decrypted_secret from vault.decrypted_secrets where name = 'supabase_service_role_key'
      )
    ),
    body := '{}'::jsonb,
    timeout_milliseconds := 120000
  );
  $$
);
