-- `cm_apps.conta_punti`: il numero di quest'app è un PUNTEGGIO?
--
-- Fino a qui la risposta stava in tre elenchi scritti a mano e da tenere
-- uguali — `APP_SENZA_PUNTI` in index.html, `AppSenzaPunti` in
-- home/PortedApps.kt e `SENZA_PUNTI` in scripts/backup-report.mjs — e se
-- divergevano le due home e la relazione settimanale mostravano tre totali
-- diversi senza che niente lo dicesse. Adesso la risposta sta sulla riga
-- dell'app, accanto alla `score_query` di cui parla: cambiare una
-- `score_query` da conteggio a punteggio (com'è successo a Memo) è una
-- modifica sola, su una riga sola.
--
-- ⚠️ `NOT NULL DEFAULT true`: un'app nuova fa punti, com'era con gli elenchi
-- (chi non ci stava dentro contava). Dire che il suo numero è un conteggio è
-- la scelta da fare esplicitamente, perché sommarlo al totale che paga i premi
-- darebbe un saldo che nessuno può rifare a mano.

ALTER TABLE public.cm_apps
  ADD COLUMN IF NOT EXISTS conta_punti boolean NOT NULL DEFAULT true;

COMMENT ON COLUMN public.cm_apps.conta_punti IS
  'Il numero della score_query è un punteggio: si scrive sotto il nome della bolla e si somma al totale che paga i premi. false = è un conteggio (giorni che mancano, transazioni, file…): dimensiona la bolla e basta.';

-- Le nove app degli elenchi di oggi, le stesse nei tre posti.
-- ⚠️ `calorie.html` ha la riga spenta e ci sta lo stesso: se tornasse accesa,
-- la sua striscia di giorni resterebbe un conteggio.
UPDATE public.cm_apps
   SET conta_punti = false
 WHERE html_file IN (
   'spuntiamola.html',
   'calorie.html',
   'obiettivi.html',
   'finanza.html',
   'casarosa.html',
   'casaterrasini.html',
   'contabilita.html',
   'cost-analysis.html',
   'forziere.html'
 );
