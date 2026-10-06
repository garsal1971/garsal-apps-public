-- Modifiche è stata tolta (pagina, APK, Edge Function modifiche-issue): qui se
-- ne vanno i dati. Tabelle mod_*, il bucket mod-immagini e le sue tre policy.
--
-- ⚠️ Lo storage la migration lo PROVA e AVVISA senza fallire, come in
-- 20261006130000_ytp_rimozione.sql: Supabase può rifiutare la DELETE diretta su
-- storage.objects/buckets, e un deploy fermo su una riga di storage si
-- porterebbe dietro tutto il resto. Se compare la NOTICE, il bucket si svuota e
-- si cancella a mano dalla dashboard (Storage → mod-immagini → Delete bucket).

DROP TABLE IF EXISTS mod_immagini;
DROP TABLE IF EXISTS mod_richieste;

DO $$
BEGIN
  DROP POLICY IF EXISTS "mod-immagini scrive"   ON storage.objects;
  DROP POLICY IF EXISTS "mod-immagini legge"    ON storage.objects;
  DROP POLICY IF EXISTS "mod-immagini cancella" ON storage.objects;
EXCEPTION WHEN OTHERS THEN
  RAISE NOTICE 'mod: policy di storage non tolte (%). Toglierle a mano.', SQLERRM;
END $$;

DO $$
BEGIN
  DELETE FROM storage.objects WHERE bucket_id = 'mod-immagini';
  DELETE FROM storage.buckets WHERE id = 'mod-immagini';
EXCEPTION WHEN OTHERS THEN
  RAISE NOTICE 'mod: bucket mod-immagini non cancellato (%). Svuotarlo e cancellarlo dalla dashboard.', SQLERRM;
END $$;
