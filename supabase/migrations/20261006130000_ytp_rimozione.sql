-- YouTube Player è stato tolto (pagina, workflow e downloader): qui se ne vanno
-- i dati. Tabelle ytp_*, la riga in cm_apps, il bucket ytp-audio e le sue policy.
--
-- ⚠️ Lo storage la migration lo PROVA e AVVISA senza fallire, come per gli altri
-- bucket: Supabase può rifiutare la DELETE diretta su storage.objects/buckets
-- («usa la Storage API»), e un deploy fermo su una riga di storage si porterebbe
-- dietro tutto il resto. Se compare la NOTICE, il bucket si svuota e si cancella
-- a mano dalla dashboard (Storage → ytp-audio → Delete bucket).

-- La bolla in home: senza la pagina aprirebbe un 404.
DELETE FROM cm_apps WHERE html_file = 'youtube-player.html';

-- Le tabelle. DROP TABLE le toglie anche dalla publication supabase_realtime.
DROP TABLE IF EXISTS ytp_playback_history;
DROP TABLE IF EXISTS ytp_player_state;
DROP TABLE IF EXISTS ytp_playlist_items;
DROP TABLE IF EXISTS ytp_playlists;

-- Le policy di storage che nominano il bucket (nate a mano, se ci sono).
DO $$
DECLARE r record;
BEGIN
  FOR r IN
    SELECT policyname FROM pg_policies
     WHERE schemaname = 'storage' AND tablename = 'objects'
       AND (coalesce(qual, '') ILIKE '%ytp-audio%' OR coalesce(with_check, '') ILIKE '%ytp-audio%')
  LOOP
    EXECUTE format('DROP POLICY IF EXISTS %I ON storage.objects', r.policyname);
  END LOOP;
EXCEPTION WHEN OTHERS THEN
  RAISE NOTICE 'ytp: policy di storage non tolte (%). Toglierle a mano.', SQLERRM;
END $$;

-- Il bucket: prima i file, poi il bucket.
DO $$
BEGIN
  DELETE FROM storage.objects WHERE bucket_id = 'ytp-audio';
  DELETE FROM storage.buckets WHERE id = 'ytp-audio';
EXCEPTION WHEN OTHERS THEN
  RAISE NOTICE 'ytp: bucket ytp-audio non cancellato (%). Svuotarlo e cancellarlo dalla dashboard.', SQLERRM;
END $$;
