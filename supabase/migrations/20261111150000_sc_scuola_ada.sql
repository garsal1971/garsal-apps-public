-- ============================================================================
-- Scuola di Ada — orario, minuti di compiti per materia, spunte del piano,
-- verifiche. Le leggono compiti-ada.html e verifiche-ada.html.
--
--   • sc_materie        una materia: sigla, nome, colore, minuti di compiti
--                       per lezione (0 = niente compiti)
--   • sc_orario         una lezione dell'orario settimanale: giorno 1-6
--                       (lun-sab), ora d'inizio 8-13, durata in ore, materia,
--                       e se quella lezione non porta compiti
--   • sc_compiti_fatti  una spunta del piano: il pomeriggio (data) in cui si
--                       sono fatti i compiti di quella lezione
--   • sc_verifiche      compiti in classe e interrogazioni
--
-- ⚠️ I DATI SONO DI SALVATORE, e Ada li legge e li modifica. `user_id` vale
-- sempre il suo (`garsal_user_id()` come DEFAULT), anche quando scrive Ada:
-- due proprietari della stessa materia sarebbero due orari.
--
-- ⚠️ L'accesso di Ada passa da `cm_guest_access` (email → pagina) e
-- `has_page_access()`, come Teresa e Rosa: basta una delle due pagine per
-- vedere tutte e quattro le tabelle, perché si leggono a vicenda.
--
-- ⚠️ Nessun dato in questa migration: orario, minuti, verifiche e la riga
-- di `cm_guest_access` si scrivono a mano nel database (la repo è pubblica).
-- ============================================================================

CREATE TABLE IF NOT EXISTS sc_materie (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     uuid NOT NULL DEFAULT public.garsal_user_id() REFERENCES auth.users(id) ON DELETE CASCADE,
  sigla       text NOT NULL CHECK (length(trim(sigla)) > 0),
  nome        text NOT NULL CHECK (length(trim(nome)) > 0),
  colore      text,
  minuti      integer NOT NULL DEFAULT 0 CHECK (minuti BETWEEN 0 AND 600),
  posizione   integer NOT NULL DEFAULT 0,
  created_at  timestamptz NOT NULL DEFAULT now(),
  UNIQUE (user_id, sigla)
);

CREATE TABLE IF NOT EXISTS sc_orario (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id        uuid NOT NULL DEFAULT public.garsal_user_id() REFERENCES auth.users(id) ON DELETE CASCADE,
  giorno         smallint NOT NULL CHECK (giorno BETWEEN 1 AND 6),   -- 1 = lunedì
  ora            smallint NOT NULL CHECK (ora BETWEEN 7 AND 14),     -- ora d'inizio
  durata         smallint NOT NULL DEFAULT 1 CHECK (durata BETWEEN 1 AND 4),
  materia_id     uuid NOT NULL REFERENCES sc_materie(id) ON DELETE CASCADE,
  senza_compiti  boolean NOT NULL DEFAULT false,
  created_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE (user_id, giorno, ora)
);

CREATE TABLE IF NOT EXISTS sc_compiti_fatti (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     uuid NOT NULL DEFAULT public.garsal_user_id() REFERENCES auth.users(id) ON DELETE CASCADE,
  giorno      date NOT NULL,                 -- il pomeriggio in cui si sono fatti
  orario_id   uuid NOT NULL REFERENCES sc_orario(id) ON DELETE CASCADE,
  fatto_il    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (user_id, giorno, orario_id)
);

CREATE TABLE IF NOT EXISTS sc_verifiche (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     uuid NOT NULL DEFAULT public.garsal_user_id() REFERENCES auth.users(id) ON DELETE CASCADE,
  data        date NOT NULL,
  materia     text NOT NULL CHECK (length(trim(materia)) > 0),
  tipo        text NOT NULL DEFAULT 'compito' CHECK (tipo IN ('compito', 'interrogazione')),
  note        text,
  created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS sc_verifiche_data_idx ON sc_verifiche (user_id, data);

-- ── Accesso: il proprietario, e l'ospite con una delle due pagine ──────────
CREATE OR REPLACE FUNCTION public.sc_ospite()
RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path TO 'public'
AS $$
  SELECT public.has_page_access('compiti-ada.html')
      OR public.has_page_access('verifiche-ada.html')
$$;
REVOKE ALL ON FUNCTION public.sc_ospite() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.sc_ospite() TO authenticated;

DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['sc_materie', 'sc_orario', 'sc_compiti_fatti', 'sc_verifiche'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON %I TO authenticated', t);
    EXECUTE format('DROP POLICY IF EXISTS %I ON %I', t || '_owner', t);
    EXECUTE format('CREATE POLICY %I ON %I FOR ALL USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid())',
                   t || '_owner', t);
    EXECUTE format('DROP POLICY IF EXISTS %I ON %I', t || '_ospite', t);
    EXECUTE format('CREATE POLICY %I ON %I FOR ALL USING (user_id = public.garsal_user_id() AND public.sc_ospite()) '
                   'WITH CHECK (user_id = public.garsal_user_id() AND public.sc_ospite())',
                   t || '_ospite', t);
  END LOOP;
END $$;
