-- Diario alimentare — anche l'USDA è una fonte che si può archiviare
-- ===========================================================================
-- `al-food-search` interroga l'USDA FoodData Central quando Open Food Facts non
-- trova niente, e restituisce quei risultati con `source = 'usda'`. Il CHECK su
-- al_foods.source ammetteva però solo base/off/manuale: un prodotto USDA si
-- poteva mangiare — i valori si copiano sulla riga del diario come per ogni
-- altro — ma non si poteva mettere in dispensa, perché l'insert veniva
-- rifiutato dal database. Andava quindi ricercato daccapo ogni volta.
--
-- ⚠️ L'alternativa scartata era archiviarlo come 'off', che è ciò che
-- `salvaAlimentoOff` faceva scrivendo la fonte a mano: passava il CHECK e
-- attribuiva a Open Food Facts un dato che Open Food Facts non ha mai visto,
-- cioè diceva il falso proprio nella colonna che esiste per dire da dove viene
-- un numero. Una riga in meno da correggere oggi, e nessun modo di sapere
-- domani quali righe correggere.
-- ---------------------------------------------------------------------------

-- ⚠️ Il vincolo si cerca per DEFINIZIONE e non per nome. Nasce inline dentro il
-- CREATE TABLE, quindi il nome glielo ha dato Postgres: `DROP CONSTRAINT IF
-- EXISTS al_foods_source_check` è l'ipotesi che l'abbia chiamato così, e se il
-- nome fosse un altro il DROP non farebbe niente **senza fallire**, l'ADD
-- riuscirebbe, e resterebbero due CHECK di cui il vecchio continuerebbe a
-- rifiutare 'usda' — cioè una migration verde e un difetto intatto.
DO $$
DECLARE c text;
BEGIN
  FOR c IN
    SELECT con.conname
      FROM pg_constraint con
      JOIN pg_class rel ON rel.oid = con.conrelid
      JOIN pg_namespace ns ON ns.oid = rel.relnamespace
     WHERE rel.relname = 'al_foods' AND ns.nspname = 'public'
       AND con.contype = 'c'
       AND pg_get_constraintdef(con.oid) ILIKE '%source%'
  LOOP
    EXECUTE format('ALTER TABLE public.al_foods DROP CONSTRAINT %I', c);
  END LOOP;
END $$;

ALTER TABLE al_foods ADD CONSTRAINT al_foods_source_check
  CHECK (source IN ('base', 'off', 'manuale', 'usda'));
