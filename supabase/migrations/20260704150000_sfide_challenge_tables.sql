-- Sfide (Ta Firi) — app sfide a tempo con punteggio a scadenza

CREATE TABLE IF NOT EXISTS sf_challenges (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id        uuid DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
  title          text NOT NULL,
  objective      text DEFAULT '',
  start_date     date NOT NULL,
  duration_days  integer NOT NULL CHECK (duration_days > 0),
  end_date       date NOT NULL,
  checkin_time   time NOT NULL DEFAULT '20:00',
  total_points   integer NOT NULL DEFAULT 0 CHECK (total_points >= 0),
  scoring_type   text NOT NULL DEFAULT 'all_or_nothing' CHECK (scoring_type IN ('all_or_nothing', 'proportional')),
  status         text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'completed', 'failed')),
  final_score    integer,
  created_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_sf_challenges_user ON sf_challenges(user_id);
ALTER TABLE sf_challenges ENABLE ROW LEVEL SECURITY;
CREATE POLICY "sf_challenges_own" ON sf_challenges FOR ALL USING (user_id = auth.uid());

CREATE TABLE IF NOT EXISTS sf_checkins (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  challenge_id   uuid NOT NULL REFERENCES sf_challenges(id) ON DELETE CASCADE,
  user_id        uuid DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
  day_date       date NOT NULL,
  status         text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'done', 'not_done')),
  checked_at     timestamptz,
  UNIQUE(challenge_id, day_date)
);
CREATE INDEX IF NOT EXISTS idx_sf_checkins_challenge ON sf_checkins(challenge_id);
CREATE INDEX IF NOT EXISTS idx_sf_checkins_user ON sf_checkins(user_id);
ALTER TABLE sf_checkins ENABLE ROW LEVEL SECURITY;
CREATE POLICY "sf_checkins_own" ON sf_checkins FOR ALL USING (user_id = auth.uid());

-- Calcola il punteggio finale di una sfida scaduta e ne aggiorna lo stato.
-- 'all_or_nothing': punti pieni solo se tutti i giorni sono 'done', altrimenti 0.
-- 'proportional':   punti proporzionali ai giorni segnati 'done' sul totale.
-- I giorni mai segnati ('pending' alla scadenza) contano come non riusciti.
CREATE OR REPLACE FUNCTION sf_finalize_challenge(p_challenge_id uuid)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_challenge   sf_challenges%ROWTYPE;
  v_done_days   integer;
  v_final_score integer;
  v_status      text;
BEGIN
  SELECT * INTO v_challenge FROM sf_challenges WHERE id = p_challenge_id AND user_id = auth.uid();

  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'error', 'sfida non trovata');
  END IF;

  IF v_challenge.status != 'active' THEN
    RETURN jsonb_build_object('ok', true, 'already_final', true, 'status', v_challenge.status, 'final_score', v_challenge.final_score);
  END IF;

  IF v_challenge.end_date >= CURRENT_DATE THEN
    RETURN jsonb_build_object('ok', false, 'error', 'sfida non ancora scaduta');
  END IF;

  SELECT COUNT(*) INTO v_done_days FROM sf_checkins WHERE challenge_id = p_challenge_id AND status = 'done';

  IF v_challenge.scoring_type = 'all_or_nothing' THEN
    v_final_score := CASE WHEN v_done_days = v_challenge.duration_days THEN v_challenge.total_points ELSE 0 END;
  ELSE
    v_final_score := ROUND(v_challenge.total_points::numeric * v_done_days / v_challenge.duration_days);
  END IF;

  v_status := CASE WHEN v_final_score > 0 THEN 'completed' ELSE 'failed' END;

  UPDATE sf_challenges
     SET status = v_status, final_score = v_final_score
   WHERE id = p_challenge_id;

  RETURN jsonb_build_object('ok', true, 'status', v_status, 'final_score', v_final_score, 'done_days', v_done_days, 'total_days', v_challenge.duration_days);
END;
$$;

-- Registra l'app nel launcher AppSphere
INSERT INTO cm_apps (title, description, score_query, color, active, html_file, riservato)
SELECT 'Sfide', 'Sfide a tempo — Ta Firi', 'SELECT COALESCE(SUM(final_score), 0)::int FROM sf_challenges WHERE user_id = auth.uid()', '#8E44AD', true, 'sfide.html', false
WHERE NOT EXISTS (SELECT 1 FROM cm_apps WHERE title = 'Sfide');
