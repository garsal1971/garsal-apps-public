-- ============================================================================
-- IA Tunnel — un prompt, un motore IA a scelta, una risposta che resta.
--
--   • ia_tunnel_messages      una riga per domanda: prompt, risposta, motore, modello
--   • ia_tunnel_tags          i tag, uno per nome e per utente
--   • ia_tunnel_message_tags  quali tag porta una domanda (anche più d'uno)
--
-- ⚠️ Tabella di collegamento e non un array `tag_ids uuid[]`: ia_tunnel_tags
-- sta in questa migration, quindi la chiave esterna si può avere davvero — e
-- fa un lavoro vero: cancellando un tag i suoi collegamenti spariscono da sé,
-- invece di restare come id che puntano al nulla. È la stessa scelta di
-- ob_action_metrics.
--
-- ⚠️ La risposta la scrive la Edge Function `ia-tunnel` col JWT dell'utente,
-- quindi sotto RLS: tutte e tre le tabelle hanno la sola policy owner.
-- ============================================================================

CREATE TABLE IF NOT EXISTS ia_tunnel_tags (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     uuid NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
  name        text NOT NULL CHECK (length(trim(name)) > 0),
  created_at  timestamptz NOT NULL DEFAULT now()
);
-- Lo stesso tag non si crea due volte, senza badare a maiuscole e spazi.
CREATE UNIQUE INDEX IF NOT EXISTS ia_tunnel_tags_nome_uq ON ia_tunnel_tags (user_id, lower(trim(name)));
ALTER TABLE ia_tunnel_tags ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS ia_tunnel_tags_owner ON ia_tunnel_tags;
CREATE POLICY ia_tunnel_tags_owner ON ia_tunnel_tags
  FOR ALL USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());

CREATE TABLE IF NOT EXISTS ia_tunnel_messages (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     uuid NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
  prompt      text NOT NULL,
  answer      text NOT NULL,
  provider    text NOT NULL,   -- gemini | qwen | groq | claude
  model       text,            -- il modello che ha risposto davvero
  created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ia_tunnel_messages_idx ON ia_tunnel_messages (user_id, created_at DESC);
ALTER TABLE ia_tunnel_messages ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS ia_tunnel_messages_owner ON ia_tunnel_messages;
CREATE POLICY ia_tunnel_messages_owner ON ia_tunnel_messages
  FOR ALL USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());

CREATE TABLE IF NOT EXISTS ia_tunnel_message_tags (
  message_id  uuid NOT NULL REFERENCES ia_tunnel_messages(id) ON DELETE CASCADE,
  tag_id      uuid NOT NULL REFERENCES ia_tunnel_tags(id) ON DELETE CASCADE,
  user_id     uuid NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
  PRIMARY KEY (message_id, tag_id)
);
CREATE INDEX IF NOT EXISTS ia_tunnel_message_tags_tag_idx ON ia_tunnel_message_tags (tag_id);
ALTER TABLE ia_tunnel_message_tags ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS ia_tunnel_message_tags_owner ON ia_tunnel_message_tags;
CREATE POLICY ia_tunnel_message_tags_owner ON ia_tunnel_message_tags
  FOR ALL USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());

-- ── la bolla in AppSphere ───────────────────────────────────────────────────
-- Il numero sono le DOMANDE fatte: un conteggio e non un punteggio, quindi
-- conta_punti = false — dimensiona la bolla ma non entra nel totale dei premi.
DO $$
DECLARE
  v_colore text;
BEGIN
  IF EXISTS (SELECT 1 FROM cm_apps WHERE html_file = 'ia-tunnel.html') THEN
    RETURN;
  END IF;
  -- ⚠️ Il colore deve essere DISTINTO: il codice della modalità nascosta è una
  -- sequenza di colori delle bolle.
  SELECT c INTO v_colore
    FROM unnest(ARRAY['#5E35B1', '#3949AB', '#7E57C2', '#512DA8', '#9575CD'])
         WITH ORDINALITY AS t(c, n)
   WHERE NOT EXISTS (SELECT 1 FROM cm_apps a WHERE upper(trim(a.color)) = upper(c))
   ORDER BY n
   LIMIT 1;

  INSERT INTO cm_apps (title, description, score_query, color, active, html_file, riservato, conta_punti)
  VALUES ('IA Tunnel',
          'Un prompt, il motore IA che vuoi, e le risposte archiviate con i loro tag',
          $q$SELECT COUNT(*)::int FROM ia_tunnel_messages WHERE user_id = auth.uid()$q$,
          v_colore, true, 'ia-tunnel.html', false, false);
END;
$$;
