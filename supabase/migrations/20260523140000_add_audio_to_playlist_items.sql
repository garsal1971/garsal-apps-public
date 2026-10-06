-- Aggiunge colonne per il download audio a ytp_playlist_items
ALTER TABLE ytp_playlist_items
    ADD COLUMN IF NOT EXISTS audio_url    TEXT,
    ADD COLUMN IF NOT EXISTS audio_status TEXT;
-- audio_status: NULL | 'pending' | 'processing' | 'ready' | 'error'
