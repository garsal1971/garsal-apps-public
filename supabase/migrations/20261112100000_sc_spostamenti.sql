-- Scuola di Ada — attività tolte o spostate dal calendario (calendario-ada.html v1.4.0).
-- Il calendario è un piano ricalcolato a ogni apertura: questa tabella dice dove
-- il piano NON deve andare da solo.
--   • `tipo`/`rif`: un compito (rif = sc_orario.id) o un pezzo di studio di un
--     argomento (rif = sc_argomenti.id). Niente chiave esterna: dipende da `tipo`.
--   • `da`: il giorno in cui il piano l'aveva messo (per un compito è anche il
--     pomeriggio con cui si spunta in sc_compiti_fatti).
--   • `min`: i minuti spostati (per un argomento, solo quel pezzo).
--   • `stato` 'cancellato': fuori dal piano, e basta (compiti e verifiche restano).
--     'rimandato': `a` è il giorno scelto; `a` NULL = sta in «da rimandare».

CREATE TABLE IF NOT EXISTS sc_spostamenti (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id    uuid NOT NULL DEFAULT public.garsal_user_id(),
  tipo       text NOT NULL CHECK (tipo IN ('compito','argomento')),
  rif        uuid NOT NULL,
  da         date NOT NULL,
  min        integer NOT NULL CHECK (min > 0),
  stato      text NOT NULL CHECK (stato IN ('cancellato','rimandato')),
  a          date,
  created_at timestamptz NOT NULL DEFAULT now(),
  CHECK (stato = 'rimandato' OR a IS NULL)
);
CREATE INDEX IF NOT EXISTS sc_spostamenti_user_idx ON sc_spostamenti (user_id);

ALTER TABLE sc_spostamenti ENABLE ROW LEVEL SECURITY;
GRANT SELECT, INSERT, UPDATE, DELETE ON sc_spostamenti TO authenticated;

DROP POLICY IF EXISTS sc_spostamenti_owner ON sc_spostamenti;
CREATE POLICY sc_spostamenti_owner ON sc_spostamenti FOR ALL
  USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());

DROP POLICY IF EXISTS sc_spostamenti_ospite ON sc_spostamenti;
CREATE POLICY sc_spostamenti_ospite ON sc_spostamenti FOR ALL
  USING (user_id = public.garsal_user_id() AND public.sc_ospite())
  WITH CHECK (user_id = public.garsal_user_id() AND public.sc_ospite());
