-- Idempotenza dell'import dei movimenti nel fondo, qualunque sia la fonte.
--
-- L'unico vincolo esistente è su (bank_connection_id, external_id), che vale solo per le
-- righe arrivate dal sync bancario: i movimenti importati dal Conto Risparmio non hanno un
-- conto collegato, quindi senza questo indice reimportarli due volte li duplicherebbe.
-- external_id per quella fonte vale 'cntrs:<id della riga di origine>'.
CREATE UNIQUE INDEX IF NOT EXISTS fnz_fund_contributions_fund_external_uq
  ON fnz_fund_contributions(fund_id, external_id)
  WHERE external_id IS NOT NULL;
