-- Argomenti di una verifica di Ada: cosa studiare, pagine e ore previste,
-- e le tre spunte (studiato, ripetuto, mi sento tranquillo).
-- Li legge e scrive verifiche-ada.html. Stessi permessi delle altre sc_*.
-- ⚠️ Nessun dato qui: gli argomenti si scrivono dalla pagina.

CREATE TABLE IF NOT EXISTS sc_argomenti (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id      uuid NOT NULL DEFAULT public.garsal_user_id() REFERENCES auth.users(id) ON DELETE CASCADE,
  verifica_id  uuid NOT NULL REFERENCES sc_verifiche(id) ON DELETE CASCADE,
  titolo       text NOT NULL CHECK (length(trim(titolo)) > 0),
  pagine       integer CHECK (pagine IS NULL OR pagine >= 0),
  ore          numeric(5,1) CHECK (ore IS NULL OR ore >= 0),
  studiato     boolean NOT NULL DEFAULT false,
  ripetuto     boolean NOT NULL DEFAULT false,
  tranquillo   boolean NOT NULL DEFAULT false,
  posizione    integer NOT NULL DEFAULT 0,
  created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS sc_argomenti_verifica_idx ON sc_argomenti (verifica_id, posizione);

ALTER TABLE sc_argomenti ENABLE ROW LEVEL SECURITY;
GRANT SELECT, INSERT, UPDATE, DELETE ON sc_argomenti TO authenticated;
DROP POLICY IF EXISTS sc_argomenti_owner ON sc_argomenti;
CREATE POLICY sc_argomenti_owner ON sc_argomenti FOR ALL
  USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());
DROP POLICY IF EXISTS sc_argomenti_ospite ON sc_argomenti;
CREATE POLICY sc_argomenti_ospite ON sc_argomenti FOR ALL
  USING (user_id = public.garsal_user_id() AND public.sc_ospite())
  WITH CHECK (user_id = public.garsal_user_id() AND public.sc_ospite());
