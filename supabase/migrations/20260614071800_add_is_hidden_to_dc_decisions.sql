-- Flag riservata su singola decisione: visibile solo in hidden mode
ALTER TABLE dc_decisions ADD COLUMN IF NOT EXISTS is_hidden BOOLEAN DEFAULT FALSE;
