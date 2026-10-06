-- Una descrizione per prodotto: che cos'è quello strumento e, se è un fondo, che
-- cosa contiene. Si legge dovunque il prodotto compaia — posizioni, movimenti,
-- prezzi, elenco prodotti in `finanza.html`, e la vista 📈 Portafoglio di
-- `situazione-teresa.html` — senza dover aprire la scheda.
--
-- ⚠️ NULLABLE e senza DEFAULT: NULL vuol dire «non l'ho ancora scritta», ed è la
-- stessa scelta delle caselle vuote di `fnz_income`. È anche quello che la ℹ️
-- guarda per decidere se comparire: con un DEFAULT '' l'icona nascerebbe su ogni
-- riga, e un'icona su tutte le righe smette di distinguere quelle che hanno
-- davvero qualcosa da dire.
--
-- ⚠️ Nessun cambio di RLS. Le policy su `fnz_products` — quella del proprietario
-- e l'ospite `guest_teresa_read_fnz_products` — sono **per riga**: una colonna
-- nuova si legge da sé, e `situazione-teresa.html` la riceve perché interroga la
-- tabella con `select=*`.
ALTER TABLE fnz_products ADD COLUMN IF NOT EXISTS description text;

COMMENT ON COLUMN fnz_products.description IS
  'Che cos''è lo strumento, in prosa: tipo di titolo e, per un fondo o un ETF, che cosa contiene. Mostrata in tooltip ovunque il prodotto compaia. NULL = non scritta (nessuna ℹ️).';
