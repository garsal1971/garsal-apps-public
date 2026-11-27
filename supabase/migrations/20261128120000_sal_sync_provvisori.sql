-- Sincronizzazione automatica del Conto Personale (Edge Function sal-sync).
--
-- 1. sal_transactions: i movimenti che somigliano a uno già in archivio (stesso importo entro
--    pochi giorni) entrano PROVVISORI, col riferimento a quello che somigliano. Restano fuori
--    dai totali finché nella pagina non si dice «Tieni» o «È un doppione».
-- 2. cm_notification_rules ammette app = 'spese': la notifica del job vuole un rule_id, e
--    sal-sync se ne crea una per canale, SPENTA (come sc-promemoria).
-- 3. Il job pg_cron: il 28 di ogni mese alle 06:13 UTC.

ALTER TABLE public.sal_transactions
  ADD COLUMN IF NOT EXISTS provvisorio boolean NOT NULL DEFAULT false,
  ADD COLUMN IF NOT EXISTS duplicato_di uuid REFERENCES public.sal_transactions(id) ON DELETE SET NULL;

ALTER TABLE public.cm_notification_rules DROP CONSTRAINT IF EXISTS cm_notification_rules_app_check;
ALTER TABLE public.cm_notification_rules ADD CONSTRAINT cm_notification_rules_app_check
  CHECK (app = ANY (ARRAY['cost_analysis','events','habits','objectives','plants','quick','scuola','spese','ta_firi','tasks','weight']));

DO $$
BEGIN
  PERFORM cron.unschedule('sal-sync') WHERE EXISTS (SELECT 1 FROM cron.job WHERE jobname = 'sal-sync');
END $$;

SELECT cron.schedule(
  'sal-sync',
  '13 6 28 * *',
  $$
  select net.http_post(
    url     := 'https://jajlmmdsjlvzgcxiiypk.supabase.co/functions/v1/sal-sync',
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
