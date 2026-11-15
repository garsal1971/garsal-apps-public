-- «Annullato» per compiti e argomenti della scuola di Ada.
-- • sc_compiti_fatti.stato: 'fatto' | 'annullato'. Nessuna riga = da fare.
--   Le righe che ci sono già erano spunte: valgono 'fatto'.
-- • sc_argomenti.annullato: l'argomento non si studia più — fuori dal piano,
--   dalle ore e dalle barre.
-- Li scrivono compiti-ada.html (spunta a tre stati), verifiche-ada.html
-- (pulsante «Annullato») e calendario-ada.html («Cancella definitivamente»).
ALTER TABLE public.sc_compiti_fatti ADD COLUMN IF NOT EXISTS stato text NOT NULL DEFAULT 'fatto';
DO $$ BEGIN
  ALTER TABLE public.sc_compiti_fatti ADD CONSTRAINT sc_compiti_fatti_stato_check CHECK (stato IN ('fatto', 'annullato'));
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

ALTER TABLE public.sc_argomenti ADD COLUMN IF NOT EXISTS annullato boolean NOT NULL DEFAULT false;
