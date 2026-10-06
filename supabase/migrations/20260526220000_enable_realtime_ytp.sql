-- Abilita Supabase Realtime per ytp_playlist_items
-- Necessario per ricevere notifiche istantanee nello script ytp-downloader.py
ALTER PUBLICATION supabase_realtime ADD TABLE ytp_playlist_items;
