-- Ripulisce i prezzi falsi di BTP10 (Amundi Ita BTP 10y, ISIN LU1598691217).
--
-- `get-prices` leggeva l'endpoint /api/etfs/{ISIN}/performance di JustETF come se
-- fosse un listino: quel `latestValue` è una *performance* in percentuale, non la
-- quota, e passava indenne il controllo `price > 0 && price < 100000`. Risultato:
-- in `fnz_price_cache` (e da lì, via trigger, in `fnz_price_history`) BTP10
-- risultava quotato poche unità di euro invece di ~157 €. La fonte è stata tolta
-- nella versione 5.18.0 della Edge Function.
--
-- Serve cancellare i valori già scritti per due motivi:
--   1. il grafico e il pannello Performance di Finanza → Prezzi li disegnano;
--   2. il nuovo controllo di plausibilità confronta il prezzo che arriva con
--      l'ultimo noto: lasciando in cache il valore sbagliato, sarebbe il prezzo
--      *giusto* a essere scartato per tre giorni (fino a `PREV_PRICE_MAX_AGE_MS`).
--
-- La soglia è 50 €: BTP10 non è mai stato sotto i 139 € da quando c'è lo storico,
-- quindi non può portare via una quotazione buona. Per lo stesso motivo lo script
-- è idempotente — rieseguito su dati sani non tocca niente.

DELETE FROM fnz_price_cache
 WHERE symbol = 'BTP10'
   AND price < 50;

DELETE FROM fnz_price_history
 WHERE symbol = 'BTP10'
   AND price < 50;
