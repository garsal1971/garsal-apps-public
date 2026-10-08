-- Asset (💎 Patrimonio) — lo storico delle valutazioni
--
-- Fino a qui un asset aveva UN valore e UNA data (`fnz_other_assets.value` / `valuation_date`), e
-- ogni modifica cancellava quello di prima: del TFR di un anno fa, o della casa stimata a marzo,
-- non restava traccia da nessuna parte.
--
-- ⚠️ Una riga per valutazione, e `fnz_other_assets.value` / `valuation_date` RESTANO: sono la
--   valutazione più recente, riscritta da un trigger a ogni scrittura su questa tabella. Così
--   Dashboard, snapshot (`save-snapshot`), 🛡️ Possibili soluzioni, Danaro di Rosa e le pagine
--   ospiti continuano a leggere la colonna di sempre senza sapere che lo storico esiste — ed è
--   il database a tenerla allineata, non una seconda scrittura che una pagina può dimenticare.
--
-- ⚠️ Cancellare l'ultima valutazione NON azzera l'asset: il trigger non trova righe e lascia la
--   colonna com'era. «Non ho più uno storico» non vuol dire «vale zero».

CREATE TABLE IF NOT EXISTS fnz_other_asset_values (
  id              uuid           PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id         uuid           NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  asset_id        uuid           NOT NULL REFERENCES fnz_other_assets(id) ON DELETE CASCADE,
  valuation_date  date           NOT NULL,
  value           numeric(18, 2) NOT NULL,
  note            text,
  created_at      timestamptz    NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS fnz_other_asset_values_asset_idx
  ON fnz_other_asset_values(asset_id, valuation_date DESC);
ALTER TABLE fnz_other_asset_values ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS "fnz_other_asset_values_own" ON fnz_other_asset_values;
CREATE POLICY "fnz_other_asset_values_own" ON fnz_other_asset_values
  FOR ALL USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());

COMMENT ON TABLE fnz_other_asset_values IS
  'Storico delle valutazioni di un asset (fnz_other_assets). La più recente (data, poi created_at) viene ricopiata da un trigger in fnz_other_assets.value / valuation_date, che restano la colonna letta da tutto il resto.';

-- Il trigger: dopo ogni scrittura riporta sull'asset la valutazione più recente.
-- SECURITY INVOKER: la riga dell'asset è dello stesso utente, e la RLS deve restare in mezzo.
CREATE OR REPLACE FUNCTION fnz_other_asset_values_sync()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  v_asset uuid;
  v_row   fnz_other_asset_values%ROWTYPE;
BEGIN
  FOR v_asset IN
    SELECT DISTINCT x FROM unnest(ARRAY[
      CASE WHEN TG_OP <> 'DELETE' THEN NEW.asset_id END,
      CASE WHEN TG_OP <> 'INSERT' THEN OLD.asset_id END]) AS x WHERE x IS NOT NULL
  LOOP
    SELECT * INTO v_row FROM fnz_other_asset_values
     WHERE asset_id = v_asset
     ORDER BY valuation_date DESC, created_at DESC
     LIMIT 1;
    IF FOUND THEN
      UPDATE fnz_other_assets
         SET value = v_row.value, valuation_date = v_row.valuation_date
       WHERE id = v_asset
         AND (value IS DISTINCT FROM v_row.value OR valuation_date IS DISTINCT FROM v_row.valuation_date);
    END IF;
  END LOOP;
  RETURN NULL;
END $$;

DROP TRIGGER IF EXISTS trg_fnz_other_asset_values_sync ON fnz_other_asset_values;
CREATE TRIGGER trg_fnz_other_asset_values_sync
  AFTER INSERT OR UPDATE OR DELETE ON fnz_other_asset_values
  FOR EACH ROW EXECUTE FUNCTION fnz_other_asset_values_sync();

-- La prima voce di ogni asset è il valore che ha oggi: senza, lo storico partirebbe vuoto e la
-- prossima valutazione sembrerebbe la prima. Idempotente: salta gli asset che una voce ce l'hanno.
INSERT INTO fnz_other_asset_values (user_id, asset_id, valuation_date, value, note)
SELECT a.user_id, a.id, a.valuation_date, a.value, 'Valore presente prima dello storico'
  FROM fnz_other_assets a
 WHERE NOT EXISTS (SELECT 1 FROM fnz_other_asset_values v WHERE v.asset_id = a.id);
