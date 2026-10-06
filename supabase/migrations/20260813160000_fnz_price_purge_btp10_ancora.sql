-- Terza ripulitura di BTP10, dopo che il valore falso si è riscritto da solo.
--
-- Cronologia del 13 agosto 2026: al mattino BTP10 stava a poche unità di euro
-- (la performance dell'API JustETF letta come prezzo). Tolta quella fonte e
-- ripulita la cache, alle 14:51 il simbolo è tornato falso con un altro numero,
-- 18,70 € contro una quota di ≈157: sbaglia anche una delle fonti che vengono
-- dopo. Quale lo dirà `fn_logs`; intanto:
--
--   * `get-prices` 5.19.0 chiede BTP10 a Yahoo col ticker `BTP10.MI`, per nome e
--     prima di tutta la catena che lo cerca per ISIN raschiando pagine;
--   * il controllo di plausibilità ha ora un secondo metro, l'ultima chiusura in
--     archivio, per i simboli la cui riga di cache è stata cancellata. Senza,
--     ripulire la cache *disarmava* il controllo — ed è esattamente per questo
--     che il 18,70 è potuto entrare dopo la ripulitura del mattino.
--
-- Questa migration toglie di mezzo il 18,70 perché il metro torni a essere
-- l'ultima quotazione buona in archivio. Stesse soglie e stessi criteri delle due
-- precedenti: metà dell'ultimo prezzo certo, e sullo storico solo le date dopo il
-- 1° giugno 2026. Idempotente.

DELETE FROM fnz_price_cache
 WHERE symbol = 'BTP10'
   AND price  < 76;

DELETE FROM fnz_price_history
 WHERE symbol     = 'BTP10'
   AND price      < 76
   AND price_date > DATE '2026-06-01';
