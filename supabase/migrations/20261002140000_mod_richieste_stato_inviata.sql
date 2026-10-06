-- 🛠️ Modifiche — issue #46: un quinto stato, «inviata».
--
-- Una richiesta mandata su GitHub con 🐙 «Manda a Claude» non è più solo «da
-- fare»: è stata consegnata, e nessuno ci sta ancora lavorando. Il giro
-- diventa aperta → inviata (🐙) → in_corso (🤖 Fai la fix) → fatta (issue
-- chiusa), più scartata.
--
-- ⚠️ Il vincolo era scritto in linea nella CREATE TABLE, quindi il nome lo ha
-- scelto Postgres: lo si cerca invece di darlo per scontato, così la migration
-- passa anche su un database dove quel nome fosse diverso.

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
  CHECK (stato IN ('aperta', 'inviata', 'in_corso', 'fatta', 'scartata'));
