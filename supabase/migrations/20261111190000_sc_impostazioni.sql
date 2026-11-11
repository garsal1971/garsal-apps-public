-- Impostazioni dello studio di Ada: le fasce orarie a disposizione per ogni
-- giorno della settimana e i minuti di pausa al giorno. Una riga sola (di
-- Salvatore, come tutte le sc_*). Le scrive impostazioni-ada.html, le legge
-- calendario-ada.html per distribuire compiti e ore delle verifiche.
--
--   fasce: {"0":[["15:00","17:00"]], "1":[…], …}  chiave = giorno JS (0 = domenica)
--
-- ⚠️ Nessun dato qui: le fasce si scrivono dalla pagina.

CREATE TABLE IF NOT EXISTS sc_impostazioni (
  user_id       uuid PRIMARY KEY DEFAULT public.garsal_user_id() REFERENCES auth.users(id) ON DELETE CASCADE,
  fasce         jsonb NOT NULL DEFAULT '{}'::jsonb,
  pausa_minuti  integer NOT NULL DEFAULT 0 CHECK (pausa_minuti BETWEEN 0 AND 600),
  updated_at    timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE sc_impostazioni ENABLE ROW LEVEL SECURITY;
GRANT SELECT, INSERT, UPDATE, DELETE ON sc_impostazioni TO authenticated;
DROP POLICY IF EXISTS sc_impostazioni_owner ON sc_impostazioni;
CREATE POLICY sc_impostazioni_owner ON sc_impostazioni FOR ALL
  USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());
DROP POLICY IF EXISTS sc_impostazioni_ospite ON sc_impostazioni;
CREATE POLICY sc_impostazioni_ospite ON sc_impostazioni FOR ALL
  USING (user_id = public.garsal_user_id() AND public.sc_ospite())
  WITH CHECK (user_id = public.garsal_user_id() AND public.sc_ospite());
