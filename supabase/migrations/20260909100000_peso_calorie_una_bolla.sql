-- Peso e Calorie — una bolla sola, perché le due app sono diventate una.
--
-- Dal 9 settembre 2026 «Ti pisasti?» e il diario alimentare sono la stessa pagina
-- (`weight-quest.html`, sette viste) e la stessa schermata sul telefono: al diario si arriva dal
-- 🍽️ nella barra di «Ti pisasti?». Erano due app che parlavano dello stesso obiettivo — la
-- seconda leggeva `ps_objectives` scritto dalla prima — quindi due bolle in home erano due porte
-- sulla stessa stanza.
--
-- ⚠️ LA RIGA DI `calorie.html` SI SPEGNE, NON SI CANCELLA. `active = false` la toglie dalla home
-- e basta; una DELETE porterebbe via anche la sua `score_query`, che è l'unico posto in cui la
-- striscia di giorni dentro il target è scritta. Quel conto oggi non serve più a nessuno — la
-- bolla che resta porta i punti di «Ti pisasti?» — ma è una cosa che qualcuno ha pensato, e
-- riscriverla da capo il giorno che servisse costa più che lasciarla lì spenta.
--
-- ⚠️ LE DUE `score_query` NON SI SOMMANO, e non è una semplificazione: quella di «Ti pisasti?»
-- dà punti veri (entrano nel totale che paga i premi), quella di «Calorie» dà una striscia di
-- giorni, cioè un conteggio — sta in `APP_SENZA_PUNTI` in `index.html` e in `AppSenzaPunti` in
-- `PortedApps.kt` proprio per questo. Sommarle vorrebbe dire un saldo che nessuno può rifare a
-- mano, e un giorno sforato che *abbassa* i punti spendibili: un premio che va e viene da sé.
-- La bolla unita tiene quindi i punti, che sono la grandezza che serviva davvero.
--
-- ⚠️ `calorie.html` RESTA COME FILE, ed è il rimando a `weight-quest.html#diario`: i segnalibri
-- e i collegamenti scritti nelle altre pagine continuano a funzionare.
--
-- ⚠️ Questa migration e la riga tolta da `PortedApps.perHtmlFile` sono la STESSA modifica e
-- vanno insieme. La riga spenta non arriva più a quel registro, quindi una voce lasciata lì
-- sarebbe inerte — ma tornerebbe a disegnare una seconda bolla il giorno che qualcuno riaccende
-- la riga, e nel nativo quella bolla aprirebbe una schermata che ormai si raggiunge dal 🍽️.
--
-- Idempotente: si può rieseguire, e su un database dove quelle righe non esistono non fa niente
-- invece di fallire (progetto dev).

UPDATE cm_apps
   SET active = false
 WHERE html_file = 'calorie.html'
   AND active IS DISTINCT FROM false;

-- Il nome della bolla dice adesso tutt'e due le cose. ⚠️ La riga di `weight-quest.html` non nasce
-- da nessuna migration — `cm_apps` fu popolata a mano prima che esistesse `migrations/` — quindi
-- qui si aggiorna e non si inserisce: un INSERT ne creerebbe una seconda accanto a quella vera.
UPDATE cm_apps
   SET title       = 'Peso e Calorie',
       description = 'Il peso e il diario alimentare'
 WHERE html_file = 'weight-quest.html';
