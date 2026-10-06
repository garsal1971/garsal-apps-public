-- YouTube Playlist Player — tabelle ytp_
-- Crea le tabelle solo se non esistono (idempotente).

CREATE TABLE IF NOT EXISTS ytp_playlists (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL,
    name        TEXT NOT NULL,
    description TEXT,
    created_at  TIMESTAMPTZ DEFAULT now(),
    updated_at  TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE IF NOT EXISTS ytp_playlist_items (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    playlist_id UUID REFERENCES ytp_playlists(id) ON DELETE CASCADE,
    youtube_id  TEXT NOT NULL,
    title       TEXT,
    position    INTEGER NOT NULL,
    added_at    TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE IF NOT EXISTS ytp_playback_history (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                UUID NOT NULL,
    playlist_id            UUID REFERENCES ytp_playlists(id) ON DELETE SET NULL,
    youtube_id             TEXT NOT NULL,
    last_position_seconds  FLOAT DEFAULT 0,
    total_duration_seconds FLOAT,
    watched_count          INTEGER DEFAULT 0,
    completed              BOOLEAN DEFAULT false,
    last_played_at         TIMESTAMPTZ DEFAULT now(),
    created_at             TIMESTAMPTZ DEFAULT now(),
    CONSTRAINT ytp_playback_history_user_video UNIQUE(user_id, youtube_id)
);

CREATE TABLE IF NOT EXISTS ytp_player_state (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id            UUID UNIQUE NOT NULL,
    playlist_id        UUID REFERENCES ytp_playlists(id) ON DELETE SET NULL,
    current_item_index INTEGER DEFAULT 0,
    updated_at         TIMESTAMPTZ DEFAULT now()
);

-- RLS
ALTER TABLE ytp_playlists        ENABLE ROW LEVEL SECURITY;
ALTER TABLE ytp_playlist_items   ENABLE ROW LEVEL SECURITY;
ALTER TABLE ytp_playback_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE ytp_player_state     ENABLE ROW LEVEL SECURITY;

DO $$ BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies WHERE tablename = 'ytp_playlists' AND policyname = 'ytp_playlists_user_owns'
  ) THEN
    CREATE POLICY ytp_playlists_user_owns ON ytp_playlists FOR ALL USING (auth.uid() = user_id);
  END IF;
END $$;

DO $$ BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies WHERE tablename = 'ytp_playback_history' AND policyname = 'ytp_playback_history_user_owns'
  ) THEN
    CREATE POLICY ytp_playback_history_user_owns ON ytp_playback_history FOR ALL USING (auth.uid() = user_id);
  END IF;
END $$;

DO $$ BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies WHERE tablename = 'ytp_player_state' AND policyname = 'ytp_player_state_user_owns'
  ) THEN
    CREATE POLICY ytp_player_state_user_owns ON ytp_player_state FOR ALL USING (auth.uid() = user_id);
  END IF;
END $$;

DO $$ BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies WHERE tablename = 'ytp_playlist_items' AND policyname = 'ytp_playlist_items_user_owns'
  ) THEN
    CREATE POLICY ytp_playlist_items_user_owns ON ytp_playlist_items FOR ALL
      USING (playlist_id IN (SELECT id FROM ytp_playlists WHERE user_id = auth.uid()));
  END IF;
END $$;
