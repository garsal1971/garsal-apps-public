-- Coperture per esigenze future (finanza.html → Previdenza → 🛡️ Coperture)
--
-- Due colonne che si guardano in faccia: da una parte quello che servirà (fabbisogno),
-- dall'altra quello con cui ci si arriva (dotazioni). La domanda a cui la pagina risponde è
-- una sola — quanto manca — quindi le due parti stanno nella stessa tabella distinte da
-- `side`, e non in due tabelle gemelle che vorrebbero due query e due insiemi di policy per
-- rispondere a una sottrazione.
--
-- ⚠️ Le VOCI non stanno qui: vivono in COVERAGE_ITEMS dentro finanza.html, come INCOME_SECTIONS
-- per il reddito. Aggiungerne una è una riga di JavaScript, non una migration; `item_key` è
-- testo libero di proposito e il vincolo che conta è UNIQUE (user_id, side, item_key), su cui
-- la pagina scrive in upsert — senza, ricompilare una voce raddoppierebbe il totale in silenzio.
--
-- ⚠️ `amount` NULL non è zero. Per le voci a mano significa «non l'ho ancora scritto»; per le
-- voci che leggono da Finanza (mutuo, portafogli, asset collegato) significa «vale il dato di
-- Finanza» — l'importo scritto a mano è un OVERRIDE che vince e si toglie col ↺. Uno zero
-- archiviato al posto del NULL bloccherebbe la voce su zero mentre il dato vero cambia sotto.

CREATE TABLE IF NOT EXISTS fnz_coverage_items (
  id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id               uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  side                  text NOT NULL CHECK (side IN ('fabbisogno', 'dotazione')),
  item_key              text NOT NULL,
  amount                numeric(14,2),
  linked_other_asset_id uuid REFERENCES fnz_other_assets(id) ON DELETE SET NULL,
  note                  text,
  created_at            timestamptz NOT NULL DEFAULT now(),
  updated_at            timestamptz NOT NULL DEFAULT now()
);

-- Una sola riga per voce: la pagina scrive in upsert su questa chiave.
CREATE UNIQUE INDEX IF NOT EXISTS uq_fnz_coverage_items_user_item
  ON fnz_coverage_items(user_id, side, item_key);
CREATE INDEX IF NOT EXISTS fnz_coverage_items_user_idx ON fnz_coverage_items(user_id);

ALTER TABLE fnz_coverage_items ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS fnz_coverage_items_own ON fnz_coverage_items;
CREATE POLICY fnz_coverage_items_own ON fnz_coverage_items FOR ALL
  USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());

COMMENT ON TABLE fnz_coverage_items IS
  'Coperture per esigenze future (finanza.html → Previdenza): fabbisogno e dotazioni, una riga per voce. Le voci vivono in COVERAGE_ITEMS dentro la pagina; qui si archiviano importo scritto a mano, asset collegato e nota.';
COMMENT ON COLUMN fnz_coverage_items.amount IS
  'Importo scritto a mano. NULL = dato assente per le voci manuali, «vale il dato di Finanza» per le voci automatiche (è un override, non un valore di ripiego).';
COMMENT ON COLUMN fnz_coverage_items.linked_other_asset_id IS
  'Riga di fnz_other_assets da cui la dotazione legge il valore (TFR, Casa Rosa, Casa Mia, Pensione Ada). ON DELETE SET NULL: cancellato l''asset la voce resta e torna a chiedere un importo, invece di sparire dal totale.';
