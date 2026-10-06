-- ============================================================
-- SOS — il pulsante rosso e il countdown che blocca il telefono
-- ============================================================
-- Un SOS è un momento di crisi ricorrente ("Binge serale", "Sigaretta dopo
-- cena"): si preme il bottone, parte un countdown durante il quale il telefono
-- resta bloccato da un overlay, e alla fine si risponde «com'è andata?».
-- Ogni risposta vale dei punti e sposta la durata del giro successivo di una
-- percentuale — in più se è andata male (serve più tempo per superarla), in
-- meno se è andata bene.
--
-- ⚠️ La regola del tempo vive QUI, non nell'APK: sos_session_finish() è
-- l'unica strada per chiudere un giro. È la stessa scelta di task_complete /
-- sf_finalize_challenge e per la stessa ragione — il client Android e la
-- pagina web darebbero due durate diverse il giorno che una delle due cambia.

-- ---------------------------------------------------------------------------
-- 1. sos_types — i diversi SOS. Nell'APK sono le pagine che si sfogliano
--    con lo swipe destra/sinistra.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sos_types (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id         uuid NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
  name            text NOT NULL,
  -- che cosa si sta fronteggiando ("desiderio di cibo"): compare sotto il
  -- nome nell'APK e in cima alla domanda finale
  description     text NOT NULL DEFAULT '',
  emoji           text NOT NULL DEFAULT '🆘',
  color           text NOT NULL DEFAULT '#EE334E',
  -- durata di partenza, quella che si ripristina con "azzera"
  base_seconds    int  NOT NULL DEFAULT 600,
  -- durata del PROSSIMO giro: è il solo valore che gli esiti riscrivono
  current_seconds int  NOT NULL DEFAULT 600,
  -- gli estremi entro cui le percentuali possono muovere current_seconds:
  -- senza, una serie di "male" porterebbe il countdown a ore
  min_seconds     int  NOT NULL DEFAULT 120,
  max_seconds     int  NOT NULL DEFAULT 3600,
  position        int  NOT NULL DEFAULT 0,
  active          boolean NOT NULL DEFAULT true,
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT sos_types_seconds_ok CHECK (
    min_seconds > 0
    AND max_seconds >= min_seconds
    AND base_seconds    BETWEEN min_seconds AND max_seconds
    AND current_seconds BETWEEN min_seconds AND max_seconds
  ),
  CONSTRAINT uq_sos_types_user_name UNIQUE (user_id, name)
);
CREATE INDEX IF NOT EXISTS idx_sos_types_user ON sos_types(user_id, position);
ALTER TABLE sos_types ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS "sos_types_own" ON sos_types;
CREATE POLICY "sos_types_own" ON sos_types FOR ALL USING (user_id = auth.uid());

-- ---------------------------------------------------------------------------
-- 2. sos_outcomes — le risposte alla domanda «com'è andata?»
--    points  → quanti punti dà (negativi se è andata male)
--    time_delta_pct → di quanto sposta la durata del giro successivo
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sos_outcomes (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id        uuid NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
  sos_type_id    uuid NOT NULL REFERENCES sos_types(id) ON DELETE CASCADE,
  label          text NOT NULL,
  emoji          text NOT NULL DEFAULT '',
  points         int  NOT NULL DEFAULT 0,
  time_delta_pct numeric(6,2) NOT NULL DEFAULT 0,
  position       int  NOT NULL DEFAULT 0,
  created_at     timestamptz NOT NULL DEFAULT now(),
  -- -90 % è già un taglio brutale e +500 % una moltiplicazione per sei:
  -- oltre non c'è una risposta sensata, c'è una cifra digitata male
  CONSTRAINT sos_outcomes_delta_ok CHECK (time_delta_pct BETWEEN -90 AND 500)
);
CREATE INDEX IF NOT EXISTS idx_sos_outcomes_type ON sos_outcomes(sos_type_id, position);
ALTER TABLE sos_outcomes ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS "sos_outcomes_own" ON sos_outcomes;
CREATE POLICY "sos_outcomes_own" ON sos_outcomes FOR ALL USING (user_id = auth.uid());

-- ---------------------------------------------------------------------------
-- 3. sos_messages — il testo motivante che scorre sotto il countdown.
--    sos_type_id NULL = frase buona per tutti i SOS.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sos_messages (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     uuid NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
  sos_type_id uuid REFERENCES sos_types(id) ON DELETE CASCADE,
  text        text NOT NULL,
  position    int  NOT NULL DEFAULT 0,
  created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_sos_messages_type ON sos_messages(user_id, sos_type_id, position);
ALTER TABLE sos_messages ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS "sos_messages_own" ON sos_messages;
CREATE POLICY "sos_messages_own" ON sos_messages FOR ALL USING (user_id = auth.uid());

-- ---------------------------------------------------------------------------
-- 4. sos_sessions — un giro di countdown, aperto alla pressione del bottone
--    e chiuso dalla risposta.
--    seconds_before/after sono la fotografia della regola applicata: senza,
--    guardando lo storico non si saprebbe più perché quel giro durava così.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sos_sessions (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id          uuid NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
  sos_type_id      uuid NOT NULL REFERENCES sos_types(id) ON DELETE CASCADE,
  started_at       timestamptz NOT NULL DEFAULT now(),
  ended_at         timestamptz,
  planned_seconds  int  NOT NULL,
  elapsed_seconds  int,
  -- false = il countdown è stato interrotto prima della fine (resa)
  completed        boolean,
  outcome_id       uuid REFERENCES sos_outcomes(id) ON DELETE SET NULL,
  outcome_label    text NOT NULL DEFAULT '',
  points           int  NOT NULL DEFAULT 0,
  time_delta_pct   numeric(6,2) NOT NULL DEFAULT 0,
  seconds_before   int,
  seconds_after    int,
  source           text NOT NULL DEFAULT 'android',
  created_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_sos_sessions_user ON sos_sessions(user_id, started_at DESC);
ALTER TABLE sos_sessions ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS "sos_sessions_own" ON sos_sessions;
CREATE POLICY "sos_sessions_own" ON sos_sessions FOR ALL USING (user_id = auth.uid());

-- ---------------------------------------------------------------------------
-- 5. sos_devices — il telefono accoppiato.
--    L'APK non fa il login Google: si accoppia una volta con un codice
--    generato da sos.html e da lì in poi parla solo con le tre RPC qui sotto.
--    Il codice È la credenziale, quindi la tabella resta leggibile al solo
--    proprietario: da PostgREST con la anon key non si arriva né a leggerla
--    né a indovinarla (32^12 combinazioni), e le RPC la consultano da
--    SECURITY DEFINER senza mai restituirla.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sos_devices (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id      uuid NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
  token        text NOT NULL UNIQUE,
  name         text NOT NULL DEFAULT 'Telefono',
  revoked      boolean NOT NULL DEFAULT false,
  last_seen_at timestamptz,
  created_at   timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE sos_devices ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS "sos_devices_own" ON sos_devices;
CREATE POLICY "sos_devices_own" ON sos_devices FOR ALL USING (user_id = auth.uid());

-- ---------------------------------------------------------------------------
-- 6. updated_at automatico su sos_types
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION sos_types_touch()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  NEW.updated_at := now();
  RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS trg_sos_types_touch ON sos_types;
CREATE TRIGGER trg_sos_types_touch
  BEFORE UPDATE ON sos_types
  FOR EACH ROW EXECUTE FUNCTION sos_types_touch();

-- ---------------------------------------------------------------------------
-- 7. Il codice di accoppiamento
--    Alfabeto senza 0/O/1/I/L: il codice si copia guardandolo da uno schermo
--    e digitandolo su un altro, dove quelle coppie si sbagliano sempre.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION sos_gen_token()
RETURNS text
LANGUAGE plpgsql VOLATILE
AS $$
DECLARE
  v_alpha text := 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
  v_out   text := '';
  i       int;
BEGIN
  FOR i IN 1..12 LOOP
    v_out := v_out || substr(v_alpha, 1 + floor(random() * length(v_alpha))::int, 1);
    IF i % 4 = 0 AND i < 12 THEN v_out := v_out || '-'; END IF;
  END LOOP;
  RETURN v_out;
END;
$$;

/** Crea un codice per il telefono. Chiamata dalla pagina web da utente loggato. */
CREATE OR REPLACE FUNCTION sos_device_create(p_name text DEFAULT 'Telefono')
RETURNS jsonb
LANGUAGE plpgsql SECURITY INVOKER
SET search_path = public
AS $$
DECLARE
  v_token text;
  v_id    uuid;
BEGIN
  IF auth.uid() IS NULL THEN
    RETURN jsonb_build_object('ok', false, 'error', 'non autenticato');
  END IF;
  LOOP
    v_token := sos_gen_token();
    EXIT WHEN NOT EXISTS (SELECT 1 FROM sos_devices WHERE token = v_token);
  END LOOP;
  INSERT INTO sos_devices (user_id, token, name)
  VALUES (auth.uid(), v_token, COALESCE(NULLIF(btrim(p_name), ''), 'Telefono'))
  RETURNING id INTO v_id;
  RETURN jsonb_build_object('ok', true, 'id', v_id, 'token', v_token);
END;
$$;

/** Risolve il codice in un utente e segna il passaggio. NULL = codice ignoto o revocato. */
CREATE OR REPLACE FUNCTION sos_user_by_token(p_token text)
RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_user uuid;
BEGIN
  SELECT user_id INTO v_user
  FROM sos_devices
  WHERE token = upper(btrim(COALESCE(p_token, ''))) AND NOT revoked;
  IF v_user IS NULL THEN RETURN NULL; END IF;
  UPDATE sos_devices SET last_seen_at = now() WHERE token = upper(btrim(p_token));
  RETURN v_user;
END;
$$;

-- ---------------------------------------------------------------------------
-- 8. sos_config — tutto quello che serve all'APK in una chiamata sola.
--    L'APK la mette in cache: al momento della crisi la rete può non esserci,
--    e un pulsante SOS che non parte perché manca la linea è un pulsante rotto.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION sos_config(p_token text)
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_user  uuid := sos_user_by_token(p_token);
  v_types jsonb;
BEGIN
  IF v_user IS NULL THEN
    RETURN jsonb_build_object('ok', false, 'error', 'codice non valido');
  END IF;

  SELECT COALESCE(jsonb_agg(t ORDER BY t.position, t.name), '[]'::jsonb) INTO v_types
  FROM (
    SELECT
      s.id, s.name, s.description, s.emoji, s.color,
      s.current_seconds AS seconds,
      s.base_seconds, s.min_seconds, s.max_seconds, s.position,
      COALESCE((SELECT SUM(x.points) FROM sos_sessions x
                 WHERE x.sos_type_id = s.id AND x.ended_at IS NOT NULL), 0) AS points_total,
      COALESCE((SELECT COUNT(*) FROM sos_sessions x
                 WHERE x.sos_type_id = s.id AND x.ended_at IS NOT NULL), 0) AS rounds_total,
      COALESCE((
        SELECT jsonb_agg(jsonb_build_object(
                 'id', o.id, 'label', o.label, 'emoji', o.emoji,
                 'points', o.points, 'time_delta_pct', o.time_delta_pct)
               ORDER BY o.position, o.label)
        FROM sos_outcomes o WHERE o.sos_type_id = s.id), '[]'::jsonb) AS outcomes,
      COALESCE((
        SELECT jsonb_agg(m.text ORDER BY m.position, m.created_at)
        FROM sos_messages m
        WHERE m.user_id = v_user AND (m.sos_type_id = s.id OR m.sos_type_id IS NULL)
      ), '[]'::jsonb) AS messages
    FROM sos_types s
    WHERE s.user_id = v_user AND s.active
  ) t;

  RETURN jsonb_build_object('ok', true, 'types', v_types, 'server_time', now());
END;
$$;

-- ---------------------------------------------------------------------------
-- 9. sos_session_start — apre il giro nel momento in cui si preme il bottone.
--    La durata la decide il server (current_seconds), non il telefono: è
--    l'unico modo perché l'esito del giro precedente valga davvero.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION sos_session_start(p_token text, p_type_id uuid, p_source text DEFAULT 'android')
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_user uuid := sos_user_by_token(p_token);
  v_type sos_types%ROWTYPE;
  v_id   uuid;
BEGIN
  IF v_user IS NULL THEN
    RETURN jsonb_build_object('ok', false, 'error', 'codice non valido');
  END IF;
  SELECT * INTO v_type FROM sos_types WHERE id = p_type_id AND user_id = v_user;
  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'error', 'SOS inesistente');
  END IF;

  INSERT INTO sos_sessions (user_id, sos_type_id, planned_seconds, seconds_before, source)
  VALUES (v_user, v_type.id, v_type.current_seconds, v_type.current_seconds,
          COALESCE(NULLIF(p_source, ''), 'android'))
  RETURNING id INTO v_id;

  RETURN jsonb_build_object('ok', true, 'session_id', v_id, 'seconds', v_type.current_seconds);
END;
$$;

-- ---------------------------------------------------------------------------
-- 10. sos_session_finish — la risposta a «com'è andata?».
--     Applica i punti e sposta la durata del giro successivo, tenendola
--     dentro min/max. Idempotente: una sessione già chiusa non si riscrive,
--     altrimenti un rinvio della coda offline dell'APK conterebbe due volte
--     gli stessi punti e sposterebbe il tempo due volte.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION sos_session_finish(
  p_token      text,
  p_session_id uuid,
  p_outcome_id uuid,
  p_completed  boolean DEFAULT true,
  p_elapsed    int     DEFAULT NULL
)
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_user    uuid := sos_user_by_token(p_token);
  v_sess    sos_sessions%ROWTYPE;
  v_out     sos_outcomes%ROWTYPE;
  v_type    sos_types%ROWTYPE;
  v_next    int;
BEGIN
  IF v_user IS NULL THEN
    RETURN jsonb_build_object('ok', false, 'error', 'codice non valido');
  END IF;

  SELECT * INTO v_sess FROM sos_sessions WHERE id = p_session_id AND user_id = v_user;
  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'error', 'giro inesistente');
  END IF;
  IF v_sess.ended_at IS NOT NULL THEN
    -- già chiusa: si risponde con quello che era stato deciso allora
    SELECT * INTO v_type FROM sos_types WHERE id = v_sess.sos_type_id;
    RETURN jsonb_build_object('ok', true, 'already', true,
      'points', v_sess.points, 'time_delta_pct', v_sess.time_delta_pct,
      'seconds_next', COALESCE(v_sess.seconds_after, v_type.current_seconds));
  END IF;

  SELECT * INTO v_out FROM sos_outcomes
   WHERE id = p_outcome_id AND user_id = v_user AND sos_type_id = v_sess.sos_type_id;
  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'error', 'risposta inesistente');
  END IF;

  SELECT * INTO v_type FROM sos_types WHERE id = v_sess.sos_type_id FOR UPDATE;

  v_next := round(v_type.current_seconds * (1 + v_out.time_delta_pct / 100.0));
  v_next := LEAST(GREATEST(v_next, v_type.min_seconds), v_type.max_seconds);

  UPDATE sos_types SET current_seconds = v_next WHERE id = v_type.id;

  UPDATE sos_sessions SET
    ended_at        = now(),
    elapsed_seconds = COALESCE(p_elapsed, planned_seconds),
    completed       = COALESCE(p_completed, true),
    outcome_id      = v_out.id,
    outcome_label   = v_out.label,
    points          = v_out.points,
    time_delta_pct  = v_out.time_delta_pct,
    seconds_before  = v_type.current_seconds,
    seconds_after   = v_next
  WHERE id = v_sess.id;

  RETURN jsonb_build_object(
    'ok', true,
    'points', v_out.points,
    'time_delta_pct', v_out.time_delta_pct,
    'seconds_prev', v_type.current_seconds,
    'seconds_next', v_next,
    'points_total', COALESCE((SELECT SUM(points) FROM sos_sessions
                               WHERE sos_type_id = v_type.id AND ended_at IS NOT NULL), 0)
  );
END;
$$;

-- Le tre RPC dell'APK sono raggiungibili dalla anon key: il controllo è il
-- codice di accoppiamento, non il ruolo. sos_user_by_token resta invece
-- interna — restituisce un user_id e non serve a nessun client.
REVOKE ALL ON FUNCTION sos_user_by_token(text) FROM anon, authenticated;
GRANT EXECUTE ON FUNCTION sos_config(text)                                   TO anon, authenticated;
GRANT EXECUTE ON FUNCTION sos_session_start(text, uuid, text)                TO anon, authenticated;
GRANT EXECUTE ON FUNCTION sos_session_finish(text, uuid, uuid, boolean, int) TO anon, authenticated;
GRANT EXECUTE ON FUNCTION sos_device_create(text)                            TO authenticated;

-- ---------------------------------------------------------------------------
-- 11. Registrazione nel launcher AppSphere
--     Il punteggio è la somma dei punti dei giri chiusi, mai sotto zero:
--     una bolla con un numero negativo sotto sembra rotta.
-- ---------------------------------------------------------------------------
INSERT INTO cm_apps (title, description, score_query, color, active, html_file, riservato)
SELECT 'SOS',
       'Il pulsante rosso: countdown bloccante nei momenti di crisi',
       'SELECT GREATEST(0, COALESCE(SUM(points), 0))::int FROM sos_sessions WHERE user_id = auth.uid() AND ended_at IS NOT NULL',
       '#EE334E', true, 'sos.html', false
WHERE NOT EXISTS (SELECT 1 FROM cm_apps WHERE title = 'SOS');

-- ---------------------------------------------------------------------------
-- 12. sos_session_log — il giro fatto senza rete, rispedito dopo.
--     Il bottone SOS deve partire anche in aereo: l'APK fa partire il
--     countdown sulla configurazione in cache e prova ad aprire la sessione
--     in parallelo. Se quella chiamata non è andata a segno non esiste
--     nessun session_id da chiudere, e senza questa RPC il giro — punti,
--     percentuale e tutto — andrebbe perso proprio nel momento in cui è
--     costato di più. Qui il giro si scrive già chiuso, e la regola del tempo
--     è la stessa perché è sos_session_finish() ad applicarla.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION sos_session_log(
  p_token      text,
  p_type_id    uuid,
  p_outcome_id uuid,
  p_started_at timestamptz,
  p_planned    int,
  p_elapsed    int     DEFAULT NULL,
  p_completed  boolean DEFAULT true
)
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_user uuid := sos_user_by_token(p_token);
  v_type sos_types%ROWTYPE;
  v_id   uuid;
BEGIN
  IF v_user IS NULL THEN
    RETURN jsonb_build_object('ok', false, 'error', 'codice non valido');
  END IF;
  SELECT * INTO v_type FROM sos_types WHERE id = p_type_id AND user_id = v_user;
  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'error', 'SOS inesistente');
  END IF;

  INSERT INTO sos_sessions (user_id, sos_type_id, started_at, planned_seconds,
                            seconds_before, source)
  VALUES (v_user, v_type.id, COALESCE(p_started_at, now()),
          COALESCE(p_planned, v_type.current_seconds),
          v_type.current_seconds, 'android-offline')
  RETURNING id INTO v_id;

  RETURN sos_session_finish(p_token, v_id, p_outcome_id, p_completed, p_elapsed);
END;
$$;

GRANT EXECUTE ON FUNCTION sos_session_log(text, uuid, uuid, timestamptz, int, int, boolean)
  TO anon, authenticated;
