-- Un quarto uso per i conti collegati: 'contribuzione' (conto-spese-teresa.html), cioè i
-- bonifici in entrata che diventano versamenti di Teresa o di Salvatore sul conto delle spese
-- comuni.
--
-- È lo STESSO conto che alimenta Spese Famiglia, non un conto in più: uses è un array proprio
-- perché un conto può servire a più moduli, quindi quel conto va spuntato sia come
-- 'cost_analysis' sia come 'contribuzione'. Il CHECK va allargato, altrimenti il battesimo
-- fallisce con una violazione di vincolo appena si spunta la casella nuova.

ALTER TABLE cm_bank_connections DROP CONSTRAINT IF EXISTS cm_bank_connections_uses_check;

ALTER TABLE cm_bank_connections
  ADD CONSTRAINT cm_bank_connections_uses_check
  CHECK (uses <@ ARRAY['cost_analysis', 'fondo', 'conto_risparmio', 'contribuzione']::text[]);

COMMENT ON COLUMN cm_bank_connections.uses IS
  'A cosa serve il conto: cost_analysis (Spese Famiglia), fondo, conto_risparmio, contribuzione. Vuoto = conto scoperto e non ancora battezzato.';
