-- Diario alimentare — grammi o millilitri
-- ===========================================================================
-- Un alimento si misurava in grammi e basta. Ma il latte, l'olio, la birra, una
-- spremuta e il caffè non si pesano: si versano, e la loro etichetta scrive i
-- valori «per 100 ml». Segnare «250 g di latte» costringeva a fingere che un
-- bicchiere fosse un peso, e la finestra della porzione chiedeva un «Peso
-- (grammi)» per una cosa che nessuno pesa mai.
--
-- ⚠️ NON è una conversione e nessuna densità entra in gioco. L'unità dice
-- soltanto SU CHE BASE sono scritti i valori nutrizionali: 100 g per un solido,
-- 100 ml per un liquido — che è esattamente come li scrive l'etichetta europea.
-- Il conto resta la stessa moltiplicazione: 250 ml × (kcal per 100 ml) / 100.
-- Convertire i ml in grammi vorrebbe dire inventare una densità (l'olio sta a
-- 0,91, il miele a 1,42) per ottenere un numero che poi verrebbe rimoltiplicato
-- per valori che quella densità l'avevano già dentro: due errori invece di zero.
--
-- ⚠️ Le colonne restano `*_100g` e `grams`, coi loro nomi: rinominarle sarebbe
-- una migration che tocca ogni query di due implementazioni e la Edge Function,
-- per non cambiare nessun numero. Il nome dice «per cento unità di questo
-- alimento», e `unit` dice quali.
--
-- ⚠️ Sta su TUTT'E DUE le tabelle, e su `al_log` non è un doppione:
-- la riga del diario porta già i valori nutrizionali congelati, per la ragione
-- che correggere un alimento domani non deve riscrivere quel che si è mangiato
-- il mese scorso. L'unità è parte di quei valori — senza, cambiare un alimento
-- da ml a g (o cancellarlo, che porta `food_id` a NULL) riscriverebbe
-- all'indietro l'etichetta di righe già segnate, o gliela toglierebbe del tutto.
--
-- ⚠️ NOT NULL DEFAULT 'g' e nessun backfill «intelligente»: tutto quel che è già
-- in archivio è stato segnato pensando ai grammi, ed è quello che è. Indovinare
-- i liquidi dal nome («latte», «olio», «succo») marcherebbe in ml anche il
-- «latte in polvere» e l'«olio di semi» di una tabella di composizione, che in
-- grammi ci stanno di proposito. Chi ha un liquido in catalogo lo apre e sceglie
-- ml: è un gesto per alimento, una volta sola.
-- ---------------------------------------------------------------------------

ALTER TABLE al_foods ADD COLUMN IF NOT EXISTS unit text NOT NULL DEFAULT 'g'
  CHECK (unit IN ('g', 'ml'));

ALTER TABLE al_log ADD COLUMN IF NOT EXISTS unit text NOT NULL DEFAULT 'g'
  CHECK (unit IN ('g', 'ml'));

COMMENT ON COLUMN al_foods.unit IS
  'Su che base sono scritti i valori nutrizionali: ''g'' = per 100 g (solidi), ''ml'' = per 100 ml (liquidi). '
  'Non è una conversione: nessuna densità entra nel conto, che resta quantità × valore_per_100 / 100.';

COMMENT ON COLUMN al_log.unit IS
  'Come va letto `grams` su questa riga: ''g'' o ''ml''. Congelata sulla riga come i valori nutrizionali — '
  'cambiare o cancellare l''alimento non deve riscrivere all''indietro quel che si è già segnato.';
