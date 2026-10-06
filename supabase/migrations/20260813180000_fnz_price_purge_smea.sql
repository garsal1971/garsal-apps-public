-- Ripulisce l'eventuale prezzo falso di SMEA (iShares Core MSCI Europe UCITS ETF
-- EUR Acc, IE00B4K48X80), che quota ≈90 €.
--
-- Il prodotto non è registrato come `etf` — nella pagina Prezzi filtrata su ETF
-- non compare — quindi finiva nel ramo delle azioni: prezzo chiesto a Twelve Data
-- col simbolo nudo «SMEA», che è il nome che il titolo ha da noi e non garantisce
-- che su TD indichi lo stesso strumento. Dalla 5.20.0 SMEA si chiede a Yahoo come
-- `SMEA.MI`, e un ticker fissato a mano viene ora prima anche di Twelve Data.
--
-- La banda cancellata non è «tutto ciò che è diverso da 90»: è **solo ciò da cui
-- il controllo di plausibilità non saprebbe più uscire da solo**. Perché un 90 €
-- corretto venga accettato, il metro precedente deve stare entro ±50 %, cioè fra
-- 60 e 180: un valore là dentro si corregge da sé al primo giro con la fonte
-- giusta, e cancellarlo sarebbe rumore. Fuori di lì, invece, resterebbe a
-- bloccare il prezzo buono.
--
-- Sullo storico solo le date dopo il 1° giugno 2026, come nelle ripuliture
-- precedenti: SMEA esiste dal 2009 e sotto i 60 € c'è stato per davvero.
-- Idempotente: se il prezzo è sano non tocca niente.

DELETE FROM fnz_price_cache
 WHERE symbol = 'SMEA'
   AND (price < 60 OR price > 180);

DELETE FROM fnz_price_history
 WHERE symbol     = 'SMEA'
   AND (price < 60 OR price > 180)
   AND price_date > DATE '2026-06-01';
