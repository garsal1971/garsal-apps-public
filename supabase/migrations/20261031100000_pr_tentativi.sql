-- Pronuncia v1.20.0: lo storico dei tentativi anche nel database, non più solo
-- sul telefono (Room). Una riga per tentativo, mani libere o esercizio.
--
-- ⚠️ Il telefono scrive PRIMA nel suo archivio e poi manda qui: senza rete i
-- tentativi restano in coda e partono alla prossima occasione. `client_id` è
-- l'id che il telefono gli ha dato (installazione + id locale): un rinvio dopo
-- una risposta persa per strada non crea un doppione, grazie all'unicità.
--
-- ⚠️ Il testo si COPIA sulla riga accanto a `frase_id`, come nello storico del
-- telefono: un testo corretto domani non riscrive quel che si è detto ieri.
--
-- Ripetizioni e punteggi per parola si ricavano da qui (COUNT/MAX/AVG): non c'è
-- una seconda tabella di contatori, che sarebbe una seconda verità.

CREATE TABLE IF NOT EXISTS public.pr_tentativi (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     uuid NOT NULL DEFAULT auth.uid() REFERENCES auth.users ON DELETE CASCADE,
  client_id   text NOT NULL,
  quando      timestamptz NOT NULL,
  giorno      date NOT NULL,
  frase_id    text NOT NULL,
  testo       text NOT NULL,
  parola      boolean NOT NULL,
  livello     text NOT NULL,
  tema        text NOT NULL,
  fonte       text NOT NULL CHECK (fonte IN ('mani_libere', 'esercizio')),
  pronuncia   numeric NOT NULL,
  accuratezza numeric NOT NULL,
  fluidita    numeric NOT NULL,
  completezza numeric NOT NULL,
  riconosciuto text NOT NULL DEFAULT '',
  -- le parole della frase: [{"p": "london", "a": 82.0, "e": "None"}]
  dettaglio   jsonb NOT NULL DEFAULT '[]'::jsonb,
  created_at  timestamptz NOT NULL DEFAULT now(),
  UNIQUE (user_id, client_id)
);

CREATE INDEX IF NOT EXISTS pr_tentativi_utente_frase ON public.pr_tentativi (user_id, frase_id);
CREATE INDEX IF NOT EXISTS pr_tentativi_utente_quando ON public.pr_tentativi (user_id, quando DESC);

ALTER TABLE public.pr_tentativi ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS pr_tentativi_proprietario ON public.pr_tentativi;
CREATE POLICY pr_tentativi_proprietario ON public.pr_tentativi
  FOR ALL TO authenticated
  USING (user_id = auth.uid())
  WITH CHECK (user_id = auth.uid());

GRANT SELECT, INSERT, UPDATE, DELETE ON public.pr_tentativi TO authenticated;
