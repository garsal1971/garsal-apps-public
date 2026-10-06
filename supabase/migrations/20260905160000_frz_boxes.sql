-- Forziere — gli scomparti.
--
-- ⚠️ SONO ORGANIZZAZIONE, NON SEPARAZIONE, e va detto perché il nome inganna: due
-- scomparti NON si proteggono a vicenda. Le 24 parole restano una sola per tutto il
-- forziere, quindi chi lo apre li apre tutti. Servono a tenere in ordine (Documenti,
-- Casa, Ada), non a chiudere qualcosa a qualcuno.
-- Il giorno che servisse la separazione vera — un forziere che l'altro non apre — quella
-- è un'altra cosa: vuol dire 24 parole per forziere, cioè N segreti da ricordare per
-- sempre, e va deciso sapendo che costa quello.
--
-- ⚠️ SU DRIVE NON CAMBIA NIENTE, ed è la scelta che regge la privacy: nessuna
-- sottocartella per scomparto. Una cartella «Divorzio» su Drive rimetterebbe in chiaro
-- esattamente il nome che qui si cifra, e vanificherebbe l'aver dato ai file nomi uuid.
-- Gli scomparti vivono SOLO nell'indice cifrato.

CREATE TABLE IF NOT EXISTS frz_boxes (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id    uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  -- ⚠️ Il nome è CIFRATO come quello dei file (AES-256-GCM sotto la chiave dell'indice):
  -- uno scomparto chiamato «Divorzio» scritto in chiaro in una colonna racconta la storia
  -- da solo, che è precisamente ciò che `frz_files.meta_enc` esiste per evitare.
  -- Ne discende che l'elenco degli scomparti si legge solo a forziere aperto — e va bene,
  -- perché prima non c'è comunque niente da vedere.
  meta_enc   text NOT NULL,
  position   integer NOT NULL DEFAULT 0,
  creato_il  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS frz_boxes_user_idx ON frz_boxes (user_id, position, creato_il);

-- ⚠️ `ON DELETE SET NULL` e non CASCADE: cancellare uno scomparto NON deve portarsi via
-- i file: tornano fuori, in «senza scomparto». Un contenitore che si porta dietro il
-- contenuto è il modo più veloce di perdere un documento per sbaglio — ed è la stessa
-- scelta di `fnz_coverage_items.linked_other_asset_id` e di `al_log.food_id`.
-- ⚠️ NULL è uno stato buono e non un dato mancante: «non l'ho messo in nessuno
-- scomparto». I file già in archivio nascono così e restano tutti visibili in «Tutti»,
-- quindi nessuna migrazione dei dati.
ALTER TABLE frz_files
  ADD COLUMN IF NOT EXISTS box_id uuid REFERENCES frz_boxes(id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS frz_files_box_idx ON frz_files (user_id, box_id);

ALTER TABLE frz_boxes ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS frz_boxes_owner ON frz_boxes;
CREATE POLICY frz_boxes_owner ON frz_boxes FOR ALL
  USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());
