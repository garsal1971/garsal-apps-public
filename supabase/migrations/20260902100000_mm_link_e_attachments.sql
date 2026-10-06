-- ============================================================
-- MEMORANDUM — schede 🔗 Link e tabella degli allegati
--
-- Una scheda `link` è UNA cosa condivisa: un video, un articolo.
-- Non è una raccolta — raccogliere lo fanno già categorie,
-- ricerca e 📌, che valgono per tutti i kind, e una scheda
-- contenitore reimplementerebbe la lista.
--
-- ⚠️ Il CHECK si allarga, non si sostituisce: 'link' si aggiunge
-- ai tre valori di prima e nessuna riga esistente cambia. Va
-- riscritto per intero perché un CHECK non si estende sul posto.
-- ============================================================

DO $$
BEGIN
  IF to_regclass('public.mm_cards') IS NULL THEN
    RAISE NOTICE 'mm_cards non esiste: migration saltata (le tabelle mm_* nascono dal SQL in memo.html → Impostazioni).';
    RETURN;
  END IF;

  ALTER TABLE mm_cards DROP CONSTRAINT IF EXISTS mm_cards_kind_check;
  ALTER TABLE mm_cards ADD CONSTRAINT mm_cards_kind_check
    CHECK (kind IN ('nota', 'lista', 'diario', 'link'));
END $$;

-- ------------------------------------------------------------
-- Allegati di una scheda
--
-- ⚠️ Si archivia l'URL e basta. Provider, id del video e
-- miniatura si RICAVANO dall'url ogni volta (`ytIdDa`,
-- `thumbDa` in memo.html): archiviarli sarebbe una seconda
-- verità sullo stesso dato, che diverge il giorno che una
-- delle due cambia. È la stessa scelta delle colonne calcolate
-- della liquidazione in `fnz_income`.
--
-- ⚠️ Nessuna colonna per Drive (storage_path, mime, size_bytes,
-- drive_file_id): oggi non le riempirebbe nessuno, e una colonna
-- che nessuno riempie è un invito a reimplementare due volte la
-- stessa cosa — le aggiunge la migration dei file, quando ci
-- saranno i file. `tipo` c'è già e ammette 'file' proprio perché
-- quel giorno non serva toccare anche il vincolo.
--
-- Il titolo è quello della scheda: una scheda = un allegato.
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS mm_attachments (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  card_id    uuid NOT NULL REFERENCES mm_cards(id) ON DELETE CASCADE,
  user_id    uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  tipo       text NOT NULL DEFAULT 'link',
  url        text NOT NULL DEFAULT '',
  position   integer NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT mm_attachments_tipo_check CHECK (tipo IN ('link', 'file'))
);

CREATE INDEX IF NOT EXISTS mm_attachments_card ON mm_attachments(card_id);

ALTER TABLE mm_attachments ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS "mm_attachments: utente vede solo i suoi" ON mm_attachments;
CREATE POLICY "mm_attachments: utente vede solo i suoi" ON mm_attachments
  FOR ALL USING (auth.uid() = user_id) WITH CHECK (auth.uid() = user_id);
