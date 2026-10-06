-- Possibili soluzioni (finanza.html → Piano pensione): una voce si può togliere dal conto
--
-- Il flag «escludi dal calcolo» sta sulla RIGA della voce, accanto a importo, nota e asset
-- collegato, perché è una proprietà di quella voce e non una preferenza di lettura: chi decide
-- che la Casa Rosa non fa parte delle dotazioni di questo piano lo decide una volta, e lo
-- ritrova dal PC come dal telefono. In cm_settings sarebbe vissuto lontano dalla voce a cui si
-- riferisce, e una voce cancellata avrebbe lasciato dietro la sua esclusione.
--
-- ⚠️ Una voce esclusa NON sparisce e NON vale zero: la pagina continua a mostrarla, sbiadita e
-- con l'importo barrato, e la toglie dai totali e dalla scopertura. Sparire sarebbe il modo
-- peggiore di escluderla — un totale più basso senza niente che dica perché — e zero direbbe
-- che quella dotazione non c'è, che è un'altra cosa da «c'è ma non la conto».
--
-- ⚠️ NOT NULL DEFAULT false: le righe già scritte sono tutte nel conto, che è quello che erano.

ALTER TABLE fnz_coverage_items
  ADD COLUMN IF NOT EXISTS excluded boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN fnz_coverage_items.excluded IS
  'La voce resta visibile ma non entra nei totali, nella scopertura né nel capitale da assicurare. Diverso da amount NULL («non l''ho scritto») e da zero («non c''è»): qui la voce c''è e si sceglie di non contarla.';
