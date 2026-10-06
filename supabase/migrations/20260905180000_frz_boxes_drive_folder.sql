-- Forziere — una cartella su Drive per ogni scomparto.
--
-- ⚠️ CAMBIA UNA SCELTA PRECEDENTE, e vale la pena dire perché. La migration degli
-- scomparti (`20260905160000`) diceva «su Drive non cambia niente: nessuna sottocartella».
-- La ragione era giusta — una cartella «Divorzio» rimette in chiaro il nome appena cifrato
-- — ma la conclusione no: il problema non erano le cartelle, era il loro NOME. Qui la
-- cartella si chiama con un **uuid**, quindi chi guarda il Drive vede esattamente quello
-- che vedeva prima (quante cartelle, quanti file, i pesi, le date) e nient'altro.
--
-- ⚠️ QUELLO CHE SI GUADAGNA è la proprietà per cui il Forziere esiste, estesa di un passo.
-- Finora: perso il database, ogni `.gpg` si riapriva lo stesso — ma erano trecento file di
-- nome `<uuid>.gpg`, e l'unico modo di sapere cosa fossero era aprirli a uno a uno. Ora
-- accanto ai file ci sono due indici, cifrati anche loro con le 24 parole:
--
--   Forziere AppSphere/
--   ├─ scomparti.gpg        quale cartella è quale scomparto
--   ├─ contenuto.gpg        i file che non stanno in nessuno scomparto
--   ├─ <uuid>.gpg           …e i file stessi
--   └─ <uuid-cartella>/     una per scomparto
--      ├─ contenuto.gpg     i file di questo scomparto
--      └─ <uuid>.gpg
--
-- `gpg -d scomparti.gpg` e `gpg -d contenuto.gpg` restituiscono nomi, tipi, date e
-- scomparti: il database smette di essere indispensabile anche per ORIENTARSI, non solo
-- per aprire. È la stessa regola del formato OpenPGP — la via di recupero dev'essere
-- ricordabile a mente — applicata all'indice invece che al singolo file.
--
-- ⚠️ LA VERITÀ RESTA IL DATABASE. Gli indici su Drive sono una copia, riscritta dopo ogni
-- operazione; se una riscrittura fallisce la pagina lo dice e in Impostazioni c'è
-- «🔄 Rifai gli indici». Senza quel pulsante sarebbero due verità che divergono in
-- silenzio, che è il difetto che questa repo paga già altrove (lo snapshot del patrimonio).

-- ⚠️ NULL è uno stato buono: «lo scomparto c'è ma la sua cartella su Drive non è ancora
-- stata creata». Ci nascono tutti gli scomparti già in archivio, e il pulsante
-- «📁 Riordina il Drive» la riempie. Un NOT NULL qui vorrebbe dire una migration che
-- inventa un id di Drive, cioè un id che non apre nessuna cartella.
ALTER TABLE frz_boxes
  ADD COLUMN IF NOT EXISTS drive_folder_id text;

COMMENT ON COLUMN frz_boxes.drive_folder_id IS
  'Id della cartella su Drive di questo scomparto. Il NOME della cartella è un uuid, non '
  'quello dello scomparto: il nome vero sta cifrato in scomparti.gpg. NULL = cartella non '
  'ancora creata, la fa «📁 Riordina il Drive».';
