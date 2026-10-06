-- Modifiche — il taccuino delle richieste di modifica alle app della suite.
--
-- Una riga per richiesta: cosa va cambiato, in quale app, e le schermate che
-- lo mostrano. Non è un'app della suite nel senso delle bolle — non ha una
-- riga in `cm_apps`, non ha un punteggio e non entra nel totale che paga i
-- premi: è il banco di lavoro dove si scrive cosa chiedere, non una cosa da
-- fare che dia punti.
--
-- ⚠️ Le immagini stanno in un bucket PRIVATO (`mod-immagini`), una cartella
-- per utente e una per richiesta: `<user_id>/<richiesta_id>/<uuid>.jpg`. È la
-- stessa forma di `mm-images` di Memo, e la prima cartella è quella su cui la
-- policy di storage confronta `auth.uid()` — cambiando la forma del percorso
-- va cambiata anche la policy, o le foto diventano illeggibili al proprietario.

-- ── le richieste ──────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS mod_richieste (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     uuid NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
  titolo      text NOT NULL,
  app         text,
  descrizione text,
  -- ⚠️ Quattro stati e non un booleano «fatta»: una richiesta scartata non è
  -- una richiesta fatta, e sparire non è una risposta — resta a schermo e lo
  -- dice, com'è `excluded` nelle Possibili soluzioni di Finanza.
  stato       text NOT NULL DEFAULT 'aperta'
              CHECK (stato IN ('aperta', 'in_corso', 'fatta', 'scartata')),
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS mod_richieste_utente_idx
  ON mod_richieste (user_id, created_at DESC);

ALTER TABLE mod_richieste ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS mod_richieste_owner ON mod_richieste;
CREATE POLICY mod_richieste_owner ON mod_richieste
  FOR ALL USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());

-- ── le immagini ───────────────────────────────────────────────────────────
-- ⚠️ `richiesta_id` è ON DELETE CASCADE: la riga se ne va con la richiesta.
-- Il file nel bucket NO — quel vincolo lo storage non lo conosce — quindi la
-- pagina cancella **prima i file e poi la riga**, come in Memo e nel Forziere:
-- nell'ordine inverso resterebbe nel bucket un'immagine che nessuna riga
-- nomina, cioè un file che nessuno sa più cos'è e che dall'app non si toglie.
CREATE TABLE IF NOT EXISTS mod_immagini (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id      uuid NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
  richiesta_id uuid NOT NULL REFERENCES mod_richieste(id) ON DELETE CASCADE,
  storage_path text NOT NULL,
  position     int  NOT NULL DEFAULT 0,
  created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS mod_immagini_richiesta_idx
  ON mod_immagini (richiesta_id, position);

ALTER TABLE mod_immagini ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS mod_immagini_owner ON mod_immagini;
CREATE POLICY mod_immagini_owner ON mod_immagini
  FOR ALL USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());

-- ── il bucket ─────────────────────────────────────────────────────────────
-- ⚠️ Se il ruolo della migration non può scrivere in `storage.buckets` la
-- migration NON muore qui: si limita ad avvisare, o un deploy fermo su una
-- riga di storage si porterebbe dietro tutto il resto. A differenza di
-- `vg-scontrini` qui non c'è nessuna Edge Function che lo crei al primo
-- caricamento: se l'avviso compare, il bucket va creato a mano dalla
-- dashboard (privato, stesso nome) — e finché non c'è, la pagina lo dice
-- invece di far sembrare riuscito un caricamento che non è avvenuto.
DO $$
BEGIN
  INSERT INTO storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
  VALUES ('mod-immagini', 'mod-immagini', false, 8388608,
          ARRAY['image/jpeg', 'image/png', 'image/webp'])
  ON CONFLICT (id) DO NOTHING;
EXCEPTION WHEN OTHERS THEN
  RAISE WARNING 'bucket mod-immagini non creato dalla migration (%): crealo a mano, privato', SQLERRM;
END;
$$;

DO $$
BEGIN
  DROP POLICY IF EXISTS "mod-immagini scrive" ON storage.objects;
  CREATE POLICY "mod-immagini scrive" ON storage.objects FOR INSERT
    WITH CHECK (bucket_id = 'mod-immagini' AND auth.uid()::text = (storage.foldername(name))[1]);

  DROP POLICY IF EXISTS "mod-immagini legge" ON storage.objects;
  CREATE POLICY "mod-immagini legge" ON storage.objects FOR SELECT
    USING (bucket_id = 'mod-immagini' AND auth.uid()::text = (storage.foldername(name))[1]);

  DROP POLICY IF EXISTS "mod-immagini cancella" ON storage.objects;
  CREATE POLICY "mod-immagini cancella" ON storage.objects FOR DELETE
    USING (bucket_id = 'mod-immagini' AND auth.uid()::text = (storage.foldername(name))[1]);
EXCEPTION WHEN OTHERS THEN
  RAISE WARNING 'policy di storage per mod-immagini non create (%): falle a mano', SQLERRM;
END;
$$;
