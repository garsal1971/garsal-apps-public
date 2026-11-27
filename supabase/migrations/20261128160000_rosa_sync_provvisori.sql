-- Sincronizzazione automatica della Cassa Casa Rosa (Edge Function cr-sync con { conto: 'rosa' }).
-- 1. cntrs_transactions: i movimenti con stessa data e importo di uno in archivio entrano
--    PROVVISORI, fuori dai totali finché nella pagina non si decide.
-- 2. Il job pg_cron: il 28 di ogni mese alle 06:33 UTC (dieci minuti dopo cr-sync).

ALTER TABLE public.cntrs_transactions
  ADD COLUMN IF NOT EXISTS provvisorio boolean NOT NULL DEFAULT false,
  ADD COLUMN IF NOT EXISTS duplicato_di uuid REFERENCES public.cntrs_transactions(id) ON DELETE SET NULL;

DO $$
BEGIN
  PERFORM cron.unschedule('rosa-sync') WHERE EXISTS (SELECT 1 FROM cron.job WHERE jobname = 'rosa-sync');
END $$;

SELECT cron.schedule(
  'rosa-sync',
  '33 6 28 * *',
  $$
  select net.http_post(
    url     := 'https://jajlmmdsjlvzgcxiiypk.supabase.co/functions/v1/cr-sync',
    headers := jsonb_build_object(
      'Content-Type', 'application/json',
      'Authorization', 'Bearer ' || (
        select decrypted_secret from vault.decrypted_secrets where name = 'supabase_service_role_key'
      )
    ),
    body := '{"conto":"rosa"}'::jsonb,
    timeout_milliseconds := 120000
  );
  $$
);
