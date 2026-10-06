-- Stessa ripulitura fatta per BTP10 in 20260813120000, estesa agli altri tre ETF
-- che passavano dalla stessa prima fonte: CHIP, INFU e IEAC.
--
-- `get-prices` leggeva /api/etfs/{ISIN}/performance di JustETF come un listino,
-- ma quel `latestValue` è una performance in percentuale: dove l'endpoint
-- rispondeva, in `fnz_price_cache` (e da lì, via trigger, in `fnz_price_history`)
-- finiva un numero di poche unità al posto della quota. La fonte è stata tolta
-- nella 5.18.0. Su BTP10 il valore falso si è visto; sugli altri tre non è stato
-- possibile controllarlo — nel codice ci sono indizi che per loro JustETF non
-- rispondesse affatto (Yahoo per CHIP, Investing.com per INFU e IEAC) — quindi
-- questo script è scritto per essere un colpo a vuoto se sono sani.
--
-- Serve comunque eseguirlo: finché un valore falso resta in cache, il nuovo
-- controllo di plausibilità confronta il prezzo in arrivo con *quello*, ed è il
-- prezzo giusto a essere scartato (fino a `PREV_PRICE_MAX_AGE_MS`, tre giorni).
--
-- Le soglie sono la metà dell'ultima quotazione buona certa (1° giugno 2026,
-- dallo storico in `dati_migrazione_auto.sql`), cioè lo stesso 50 % di
-- `MAX_PRICE_DEVIATION`: sotto quel livello non è un movimento di mercato.
--
--   simbolo | 2026-06-01 | soglia
--   BTP10   |     153.95 |     76
--   CHIP    |     116.94 |     58
--   INFU    |     141.14 |     70
--   IEAC    |     119.72 |     59
--
-- Sullo storico la cancellazione è limitata alle date successive al 1° giugno
-- 2026: prima di quella data i dati sono verificati e alcuni minimi veri stanno
-- sotto la soglia — CHIP ha davvero quotato 18,31 € nel 2022, e una soglia secca
-- se lo porterebbe via. La cache non ha bisogno del vincolo di data: è transitoria
-- e si riscrive al giro successivo.
--
-- Idempotente: su dati sani nessuna riga corrisponde.

WITH soglie(symbol, floor_price) AS (
  VALUES ('BTP10', 76.0), ('CHIP', 58.0), ('INFU', 70.0), ('IEAC', 59.0)
)
DELETE FROM fnz_price_cache c
 USING soglie s
 WHERE c.symbol = s.symbol
   AND c.price  < s.floor_price::numeric;

WITH soglie(symbol, floor_price) AS (
  VALUES ('BTP10', 76.0), ('CHIP', 58.0), ('INFU', 70.0), ('IEAC', 59.0)
)
DELETE FROM fnz_price_history h
 USING soglie s
 WHERE h.symbol     = s.symbol
   AND h.price      < s.floor_price::numeric
   AND h.price_date > DATE '2026-06-01';
