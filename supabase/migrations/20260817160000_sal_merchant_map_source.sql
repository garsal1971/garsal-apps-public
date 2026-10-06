-- Spese Personali — regole di attribuzione separate per fonte.
--
-- Lo stesso movimento arriva scritto in due modi diversi a seconda di come entra: il sync della
-- banca cuce nella descrizione il nome della controparte («… BAR CENTRALE ROMA · BAR CENTRALE
-- SRL»), il foglio Excel porta la sola colonna descrizione («… BAR CENTRALE ROMA»). Una chiave
-- imparata su una fonte quindi non dice niente sull'altra: teneva insieme due set che non sono
-- confrontabili, e ogni correzione fatta da una parte sballava l'altra.
--
-- Da qui in poi sono due elenchi distinti e un movimento consulta SOLO le regole della sua
-- fonte, che è già scritta in sal_transactions.import_source ('bank' | 'excel'): nessuna colonna
-- nuova serve dalla parte dei movimenti.

ALTER TABLE sal_merchant_map
  ADD COLUMN IF NOT EXISTS source text NOT NULL DEFAULT 'bank'
  CHECK (source IN ('bank', 'excel'));

-- Le regole che esistono oggi sono nate tutte dalle descrizioni della banca: 'bank' è il valore
-- giusto, non un ripiego, e il DEFAULT le sistema da sé. Non vengono copiate nel set 'excel' —
-- scelta esplicita: quelle chiavi contengono pezzi di testo che in un foglio non compaiono, e
-- copiarle riempirebbe il set nuovo di regole che non agganciano niente.

-- L'unicità diventa per fonte, o la stessa chiave non potrebbe esistere nei due elenchi: è
-- proprio il caso normale, lo stesso negozio imparato da tutt'e due le parti.
DROP INDEX IF EXISTS uq_sal_merchant_map_user_key;
CREATE UNIQUE INDEX IF NOT EXISTS uq_sal_merchant_map_user_source_key
  ON sal_merchant_map(user_id, source, merchant_key);

COMMENT ON COLUMN sal_merchant_map.source IS
  'Da quale fonte è stata imparata la regola: bank (sync Enable Banking) | excel (import da file). Un movimento consulta solo le regole della propria import_source: le descrizioni delle due fonti non sono confrontabili.';
