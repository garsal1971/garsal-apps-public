-- Spese Ada — regole di attribuzione separate per fonte.
--
-- Stessa ragione di sal_merchant_map (20260817160000): da quando i movimenti possono arrivare
-- anche da un foglio Excel, la stessa spesa entra scritta in due modi diversi — il sync della
-- banca cuce nella descrizione il nome della controparte, il foglio porta la sola colonna
-- descrizione — e una chiave imparata su una fonte non dice niente sull'altra. Tenerle in un
-- elenco solo vorrebbe dire che ogni correzione fatta da una parte sballa l'altra.
--
-- Dalla parte dei movimenti non serve niente: ada_transactions.import_source ('bank' | 'excel')
-- c'è dal principio e dice già da dove è arrivata ogni riga.

ALTER TABLE ada_merchant_map
  ADD COLUMN IF NOT EXISTS source text NOT NULL DEFAULT 'bank'
  CHECK (source IN ('bank', 'excel'));

-- Le regole che esistono oggi sono nate tutte dalle descrizioni della banca, che era l'unica
-- strada: 'bank' è il valore giusto e non un ripiego, e il DEFAULT le sistema da sé. Non vengono
-- copiate nel set 'excel' — stessa scelta esplicita fatta per Spese Personali: quelle chiavi
-- contengono pezzi di testo che in un foglio non compaiono, e copiarle riempirebbe l'elenco nuovo
-- di regole che non aggancerebbero mai niente.

-- L'unicità diventa per fonte, o la stessa chiave non potrebbe esistere nei due elenchi: è
-- proprio il caso normale, lo stesso negozio imparato da tutt'e due le parti.
DROP INDEX IF EXISTS uq_ada_merchant_map_user_key;
CREATE UNIQUE INDEX IF NOT EXISTS uq_ada_merchant_map_user_source_key
  ON ada_merchant_map(user_id, source, merchant_key);

COMMENT ON COLUMN ada_merchant_map.source IS
  'Da quale fonte è stata imparata la regola: bank (sync Enable Banking) | excel (import da file). Un movimento consulta solo le regole della propria import_source: le descrizioni delle due fonti non sono confrontabili.';
