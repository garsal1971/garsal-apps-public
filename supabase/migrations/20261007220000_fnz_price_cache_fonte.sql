-- get-prices: ricordare la fonte che ha dato l'ultimo prezzo di ogni simbolo.
--
-- `source`     la fonte (es. 'justetf', 'yahoo_isin', 'euronext', 'td', 'soldionline').
-- `source_ref` l'identificativo usato lì quando la fonte ne vuole uno: il ticker Yahoo
--              trovato dall'ISIN, il simbolo Twelve Data risolto, il mercato Euronext.
--
-- Al giro dopo get-prices prova per prima quella fonte con quell'identificativo, e solo
-- se fallisce ripercorre la catena completa. Senza, ogni ora ogni ETF ripartiva da
-- JustETF e ogni ricerca ISIN → ticker si rifaceva da capo.
--
-- Nullable e senza DEFAULT di proposito: NULL vuol dire «non lo so ancora», e il primo
-- giro fa la catena completa come prima. Il trigger verso fnz_price_history copia solo
-- simbolo e prezzo, quindi non c'è altro da toccare.
ALTER TABLE fnz_price_cache
  ADD COLUMN IF NOT EXISTS source     text,
  ADD COLUMN IF NOT EXISTS source_ref text;
