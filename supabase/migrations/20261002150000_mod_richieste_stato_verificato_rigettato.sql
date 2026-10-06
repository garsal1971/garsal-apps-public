-- 🛠️ Modifiche — issue #47: due stati in più, «verificato» e «rigettato».
--
-- Dopo «fatta» (la issue è chiusa) tocca all'operatore guardare se la modifica
-- è davvero a posto: a mano la porta a «verificato», oppure la «rigetta» — e il
-- rigetto crea da sé una nuova richiesta clonando quella iniziale con la
-- risposta. Niente colonne nuove e nessun dato toccato: cambia solo il CHECK.
--
-- ⚠️ Stessa tecnica della migration di «inviata»: il nome del vincolo si cerca
-- invece di darlo per scontato.

DO $$
DECLARE
  v_nome text;
BEGIN
  FOR v_nome IN
    SELECT conname FROM pg_constraint
     WHERE conrelid = 'public.mod_richieste'::regclass
       AND contype = 'c'
       AND pg_get_constraintdef(oid) ILIKE '%stato%'
  LOOP
    EXECUTE format('ALTER TABLE public.mod_richieste DROP CONSTRAINT %I', v_nome);
  END LOOP;
END $$;

ALTER TABLE public.mod_richieste
  ADD CONSTRAINT mod_richieste_stato_check
  CHECK (stato IN ('aperta', 'inviata', 'in_corso', 'fatta', 'verificato', 'rigettato', 'scartata'));
