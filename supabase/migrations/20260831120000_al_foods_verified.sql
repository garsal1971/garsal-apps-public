-- Diario alimentare — «dati verificati»
-- ===========================================================================
-- Un alimento in `al_foods` può arrivare da tre parti, e nessuna delle tre dice
-- se quel numero è giusto: le voci 'base' sono valori indicativi presi dalle
-- tabelle di composizione pubbliche, 'off' e 'usda' li scrivono i collaboratori
-- di banche dati aperte — capita che manchino o siano sbagliati — e 'manuale'
-- l'ha copiato una persona da un'etichetta, di fretta. `source` dice DA DOVE
-- viene un numero; non dice se qualcuno l'ha guardato.
--
-- È la stessa domanda che ha lasciato la «Pizza condita» in archivio a 1225 kcal
-- per 100 g: la riga sembrava una riga qualunque, e non c'era nessun posto in cui
-- dire «questa l'ho controllata contro l'etichetta».
--
-- ⚠️ NON è una seconda `source` e non si ricava da lei: sono due cose ortogonali
-- — un prodotto Open Food Facts confrontato con l'etichetta che si ha in mano è
-- verificato, una voce scritta a mano di fretta non lo è. Ricavarla dalla fonte
-- vorrebbe dire che «verificato» smette di significare «l'ho guardato io».
--
-- ⚠️ NOT NULL DEFAULT false, e quindi tutte le righe già in archivio nascono NON
-- verificate — comprese le 'base' e comprese quelle scritte a mano. Non è un
-- ripiego: la spunta significa «l'ho controllato», e metterla d'ufficio su righe
-- che nessuno ha mai riguardato direbbe il falso proprio nella colonna che esiste
-- per dire se ci si può fidare. Un catalogo tutto verificato al primo avvio non
-- distingue più niente.
--
-- ⚠️ Un booleano e non una data di verifica: qui la domanda è «me ne fido?», e la
-- data sarebbe un secondo dato da tenere aggiornato per rispondere alla stessa.
-- Se un giorno servirà sapere QUANDO, si aggiunge allora — `updated_at` intanto
-- dice quando la riga è stata toccata l'ultima volta.
--
-- Nessun backfill e nessuna eccezione: chi vuole partire dalle voci base le apre
-- e le spunta, che è esattamente il gesto che la colonna archivia.
-- ---------------------------------------------------------------------------

ALTER TABLE al_foods ADD COLUMN IF NOT EXISTS verified boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN al_foods.verified IS
  'true = i valori nutrizionali sono stati controllati da chi usa l''app (etichetta o tabella alla mano). '
  'Indipendente da `source`: dice se il numero è stato guardato, non da dove viene. '
  'Le righe già in archivio nascono false — la spunta la mette una persona, non una migration.';
