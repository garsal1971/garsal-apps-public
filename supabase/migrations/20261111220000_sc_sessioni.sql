-- Scuola di Ada — sessioni di studio cronometrate (studio-ada.html).
-- Una riga per tratto di tempo: un compito, un argomento o una pausa.
-- `rif` è l'id della lezione (sc_orario) per un compito, dell'argomento
-- (sc_argomenti) per un argomento, NULL per la pausa. Niente chiave esterna:
-- una lezione o un argomento cancellati non devono portarsi via il tempo fatto.
-- `fine` NULL = il cronometro sta ancora girando.

CREATE TABLE IF NOT EXISTS sc_sessioni (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id    uuid NOT NULL DEFAULT public.garsal_user_id(),
  giorno     date NOT NULL,
  tipo       text NOT NULL CHECK (tipo IN ('compito','argomento','pausa')),
  rif        uuid,
  titolo     text NOT NULL DEFAULT '',
  inizio     timestamptz NOT NULL DEFAULT now(),
  fine       timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  CHECK (fine IS NULL OR fine >= inizio)
);
CREATE INDEX IF NOT EXISTS sc_sessioni_giorno_idx ON sc_sessioni (user_id, giorno);

ALTER TABLE sc_sessioni ENABLE ROW LEVEL SECURITY;
GRANT SELECT, INSERT, UPDATE, DELETE ON sc_sessioni TO authenticated;

DROP POLICY IF EXISTS sc_sessioni_owner ON sc_sessioni;
CREATE POLICY sc_sessioni_owner ON sc_sessioni FOR ALL
  USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());

DROP POLICY IF EXISTS sc_sessioni_ospite ON sc_sessioni;
CREATE POLICY sc_sessioni_ospite ON sc_sessioni FOR ALL
  USING (user_id = public.garsal_user_id() AND public.sc_ospite())
  WITH CHECK (user_id = public.garsal_user_id() AND public.sc_ospite());
