-- Pronuncia v1.37.0: le parole e le frasi viste a lezione, da ripassare
-- nell'app accanto al catalogo dell'APK (tema «📘 Corso»).
--
-- Le righe le inserisce Claude a fine lezione con l'SQL, e si correggono dalla
-- dashboard: l'app le legge e basta (sola lettura, come pr_traduzioni).
-- ⚠️ Nessun dato in questa migration: le voci di una lezione si inseriscono e
-- non si committano.
--
-- ⚠️ L'app le cita nello storico come `c-<id>`: l'id non si cambia e non si
-- riusa. Cancellare una riga la toglie dal ripasso, i tentativi restano.

CREATE TABLE IF NOT EXISTS public.pr_parole_corso (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  testo       text NOT NULL CHECK (btrim(testo) <> ''),
  traduzione  text NOT NULL CHECK (btrim(traduzione) <> ''),
  tipo        text NOT NULL CHECK (tipo IN ('parola', 'frase')),
  origine     text NOT NULL,
  inserita_il timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE public.pr_parole_corso ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS pr_parole_corso_lettura ON public.pr_parole_corso;
CREATE POLICY pr_parole_corso_lettura ON public.pr_parole_corso
  FOR SELECT TO authenticated USING (true);

GRANT SELECT ON public.pr_parole_corso TO authenticated;
