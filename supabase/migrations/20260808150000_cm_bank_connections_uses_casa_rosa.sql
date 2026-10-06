-- Sesto uso per i conti collegati: 'casa_rosa' (casarosa.html), il conto UniCredit da cui la
-- Cassa Casa Rosa legge i suoi movimenti.
--
-- Uso a sé e non 'conto_risparmio' riusato: quello elenca i conti del Conto Risparmio di Teresa,
-- e le due pagine si ritroverebbero ciascuna il conto dell'altra nella tendina dell'import —
-- con due contabilità diverse sullo stesso schema, sbagliare conto è un errore che poi si paga
-- a mano.

ALTER TABLE cm_bank_connections DROP CONSTRAINT IF EXISTS cm_bank_connections_uses_check;
ALTER TABLE cm_bank_connections
  ADD CONSTRAINT cm_bank_connections_uses_check
  CHECK (uses <@ ARRAY['cost_analysis', 'fondo', 'conto_risparmio', 'contribuzione', 'spese_ada', 'casa_rosa']::text[]);

COMMENT ON COLUMN cm_bank_connections.uses IS
  'A cosa serve il conto: cost_analysis (Spese Famiglia), fondo, conto_risparmio, contribuzione, spese_ada, casa_rosa. Vuoto = conto scoperto e non ancora battezzato.';
