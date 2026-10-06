-- ============================================================================
-- IA Tunnel diventa «Chiedi a Pino», e un prompt porta con sé fino a 4 immagini.
--
--   • ia_tunnel_images   le immagini allegate a una domanda (i file stanno nel
--                        bucket PRIVATO `ia-tunnel-images`,
--                        `<user_id>/<message_id>/<uuid>.jpg`)
--   • cm_apps            la bolla si chiama «Chiedi a Pino» e apre
--                        chiedi-a-pino.html
--
-- ⚠️ I nomi interni restano ia_tunnel_* e ia-tunnel, di proposito: a cambiare
-- sono il file, il titolo e la bolla. Rinominare tabelle e funzione sarebbe
-- una migration e un deploy in più senza cambiare niente di quel che si vede.
--
-- ⚠️ Il percorso porta l'id della DOMANDA e la domanda nasce DOPO le immagini:
-- l'id lo genera la pagina e lo passa alla Edge Function, che crea la riga
-- con quello. Così i file si caricano prima, la funzione li manda all'IA, e se
-- l'IA non risponde la pagina li ricancella.
-- ============================================================================

CREATE TABLE IF NOT EXISTS ia_tunnel_images (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id       uuid NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
  message_id    uuid NOT NULL REFERENCES ia_tunnel_messages(id) ON DELETE CASCADE,
  storage_path  text NOT NULL,
  position      integer NOT NULL DEFAULT 0,
  created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ia_tunnel_images_msg_idx ON ia_tunnel_images (message_id, position);
ALTER TABLE ia_tunnel_images ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS ia_tunnel_images_owner ON ia_tunnel_images;
CREATE POLICY ia_tunnel_images_owner ON ia_tunnel_images
  FOR ALL USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());

-- ── il bucket ───────────────────────────────────────────────────────────────
-- ⚠️ Se non passa la migration non muore: avvisa. Il bucket va allora creato
-- a mano (privato, stesso nome), e la pagina lo dice al primo caricamento.
DO $$
BEGIN
  INSERT INTO storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
  VALUES ('ia-tunnel-images', 'ia-tunnel-images', false, 8388608,
          ARRAY['image/jpeg', 'image/png', 'image/webp'])
  ON CONFLICT (id) DO NOTHING;
EXCEPTION WHEN OTHERS THEN
  RAISE WARNING 'bucket ia-tunnel-images non creato dalla migration (%): crealo a mano, privato', SQLERRM;
END;
$$;

DO $$
BEGIN
  DROP POLICY IF EXISTS "ia-tunnel-images scrive" ON storage.objects;
  CREATE POLICY "ia-tunnel-images scrive" ON storage.objects FOR INSERT
    WITH CHECK (bucket_id = 'ia-tunnel-images' AND auth.uid()::text = (storage.foldername(name))[1]);

  DROP POLICY IF EXISTS "ia-tunnel-images legge" ON storage.objects;
  CREATE POLICY "ia-tunnel-images legge" ON storage.objects FOR SELECT
    USING (bucket_id = 'ia-tunnel-images' AND auth.uid()::text = (storage.foldername(name))[1]);

  DROP POLICY IF EXISTS "ia-tunnel-images cancella" ON storage.objects;
  CREATE POLICY "ia-tunnel-images cancella" ON storage.objects FOR DELETE
    USING (bucket_id = 'ia-tunnel-images' AND auth.uid()::text = (storage.foldername(name))[1]);
EXCEPTION WHEN OTHERS THEN
  RAISE WARNING 'policy di storage per ia-tunnel-images non create (%): falle a mano', SQLERRM;
END;
$$;

-- ── la bolla ────────────────────────────────────────────────────────────────
-- Colore, score_query e conta_punti restano quelli di prima: cambiano il
-- nome e il file che la bolla apre.
UPDATE cm_apps
   SET title       = 'Chiedi a Pino',
       description = 'Un prompt (con le sue immagini), il motore IA che vuoi, e le risposte archiviate con i loro tag',
       html_file   = 'chiedi-a-pino.html'
 WHERE html_file = 'ia-tunnel.html';
