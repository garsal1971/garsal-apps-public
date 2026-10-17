-- ============================================================================
-- Scorte Amazon — i prezzi visti nei negozi, per confrontarli con Amazon.
--
-- Una riga per prezzo rilevato: di quale abbonamento, quando, se è il prezzo
-- di UN PEZZO o quello AL KG/LITRO (la barra che si è toccata), il prezzo, il
-- negozio (facoltativo) e dov'era il telefono.
--
-- ⚠️ lat/lon NULL = posizione non avuta (permesso negato, niente segnale): il
-- prezzo vale lo stesso, la riga lo dice. Non è zero, che è un punto nell'oceano.
-- ⚠️ Nessun dato in questa migration: la repo è pubblica.
-- ============================================================================

CREATE TABLE IF NOT EXISTS amz_prezzi_negozio (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id         uuid NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
  abbonamento_id  uuid NOT NULL REFERENCES amz_abbonamenti(id) ON DELETE CASCADE,
  rilevato_il     timestamptz NOT NULL DEFAULT now(),
  unita           text NOT NULL CHECK (unita IN ('pezzo', 'kg_l')),
  prezzo          numeric NOT NULL CHECK (prezzo >= 0),
  negozio         text,
  lat             double precision CHECK (lat BETWEEN -90 AND 90),
  lon             double precision CHECK (lon BETWEEN -180 AND 180),
  precisione_m    numeric CHECK (precisione_m >= 0),
  created_at      timestamptz NOT NULL DEFAULT now(),
  CHECK ((lat IS NULL) = (lon IS NULL))
);
CREATE INDEX IF NOT EXISTS amz_prezzi_negozio_abb_idx ON amz_prezzi_negozio (abbonamento_id, unita, rilevato_il DESC);
ALTER TABLE amz_prezzi_negozio ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS amz_prezzi_negozio_owner ON amz_prezzi_negozio;
-- ⚠️ Il controllo sull'abbonamento non è prudenza generica: con il solo user_id
-- si potrebbe appendere un prezzo all'abbonamento di un altro, di cui si sa l'id.
CREATE POLICY amz_prezzi_negozio_owner ON amz_prezzi_negozio
  FOR ALL USING (user_id = auth.uid())
  WITH CHECK (user_id = auth.uid()
              AND EXISTS (SELECT 1 FROM amz_abbonamenti a WHERE a.id = abbonamento_id AND a.user_id = auth.uid()));
GRANT SELECT, INSERT, UPDATE, DELETE ON amz_prezzi_negozio TO authenticated;
