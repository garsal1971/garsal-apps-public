-- Possibili soluzioni (finanza.html → Piano pensione): la deduzione fiscale sulla contribuzione
--
-- La contribuzione volontaria alla pensione è DEDUCIBILE dal reddito (art. 10 TUIR): quello che
-- si versa abbassa l'imponibile IRPEF, quindi costa meno di quanto è scritto. La spunta dice se
-- quella voce del fabbisogno va contata al lordo (quanto si versa) o al netto del risparmio
-- d'imposta (quanto costa davvero).
--
-- ⚠️ Il flag sta sulla RIGA della voce, accanto a importo, nota, esclusione e asset collegato,
-- per la stessa ragione di `excluded`: è una proprietà di quella voce e non una preferenza di
-- lettura. In cm_settings sarebbe vissuto lontano dalla voce a cui si riferisce.
--
-- ⚠️ La colonna dice solo SE applicarla. Il reddito su cui si calcola (l'ultimo anno pieno di
-- fnz_pension_contributions) e gli scaglioni IRPEF vivono in finanza.html, come le voci in
-- COVERAGE_ITEMS: archiviare qui il risparmio calcolato sarebbero due verità sullo stesso
-- numero, che divergono il giorno che il reddito o gli scaglioni cambiano.
--
-- ⚠️ NOT NULL DEFAULT false: le righe già scritte sono tutte al lordo, che è quello che erano —
-- e una deduzione messa d'ufficio su una voce che nessuno ha riguardato abbasserebbe in silenzio
-- il fabbisogno di un piano già letto.

ALTER TABLE fnz_coverage_items
  ADD COLUMN IF NOT EXISTS applica_detrazione boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN fnz_coverage_items.applica_detrazione IS
  'La voce si conta al netto del risparmio IRPEF che la sua deducibilità produce (contribuzione volontaria alla pensione). Il risparmio si ricalcola sempre in pagina: qui c''è solo la scelta, non il numero.';
