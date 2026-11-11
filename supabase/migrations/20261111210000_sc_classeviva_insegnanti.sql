-- ClasseViva: due tipi in più in sc_classeviva — gli insegnanti (dalle API,
-- /students/{id}/subjects) e i ricevimenti (dalla pagina dei colloqui del sito,
-- già ridotta a righe da da-classeviva.html). Nessun dato qui: la repo è pubblica.
ALTER TABLE sc_classeviva DROP CONSTRAINT IF EXISTS sc_classeviva_tipo_check;
ALTER TABLE sc_classeviva ADD CONSTRAINT sc_classeviva_tipo_check
  CHECK (tipo IN ('voti', 'agenda', 'lezioni', 'insegnanti', 'ricevimenti'));
