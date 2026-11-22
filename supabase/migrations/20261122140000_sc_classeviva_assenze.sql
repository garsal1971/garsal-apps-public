-- ClasseViva: un tipo in più in sc_classeviva — le assenze (assenze, ritardi e
-- uscite anticipate da /students/{id}/absences/details), lette dall'APK come gli
-- altri tipi. Nessun dato qui: la repo è pubblica.
ALTER TABLE sc_classeviva DROP CONSTRAINT IF EXISTS sc_classeviva_tipo_check;
ALTER TABLE sc_classeviva ADD CONSTRAINT sc_classeviva_tipo_check
  CHECK (tipo IN ('voti', 'agenda', 'lezioni', 'insegnanti', 'ricevimenti', 'assenze'));
