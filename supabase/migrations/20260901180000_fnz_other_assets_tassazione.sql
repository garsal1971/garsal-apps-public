-- Asset (💎 Patrimonio) — regime fiscale e costo di acquisto
--
-- I prodotti dei portafogli hanno già il tag TASSAZIONE, da cui `finanza.html` ricava il valore
-- al netto delle imposte sulle plusvalenze. Gli asset no: un TFR, una casa o un BTP entrano nei
-- totali al lordo, e nelle Coperture per esigenze future finiscono fra le DOTAZIONI — cioè fra
-- quello con cui ci si arriva davvero, dove il lordo dice una cosa che non è vera, perché
-- l'imposta si paga proprio nel momento in cui quella dotazione servirebbe.
--
-- ⚠️ Due colonne e non una, perché il regime da solo non basta a fare un conto:
--   `tax_regime` dice CON QUALE aliquota, `cost_basis` SU QUALE base. Senza il costo di
--   acquisto una plusvalenza non è calcolabile, e stimarla sul valore intero darebbe una tassa
--   enorme e falsa su una casa comprata trent'anni fa.
--
-- ⚠️ `TFR SEPARATA` è l'eccezione ed è il motivo per cui esiste: il TFR è tassato PER INTERO a
--   tassazione separata, non sulla sola rivalutazione — lì il costo di acquisto non c'entra e
--   non serve.
--
-- ⚠️ NULL non è «26 %». Sui prodotti finanziari un tag assente vale l'aliquota ordinaria, perché
--   quello è il regime di default di uno strumento finanziario; su un asset qualunque — una
--   casa, un'auto, un credito — non esiste un default sensato, e inventarne uno metterebbe una
--   tassa dove non c'è. Regime assente = nessuna imposta stimata, e la pagina lo dice.

ALTER TABLE fnz_other_assets
  ADD COLUMN IF NOT EXISTS tax_regime text,
  ADD COLUMN IF NOT EXISTS cost_basis numeric(18,2);

ALTER TABLE fnz_other_assets DROP CONSTRAINT IF EXISTS fnz_other_assets_tax_regime_check;
ALTER TABLE fnz_other_assets
  ADD CONSTRAINT fnz_other_assets_tax_regime_check
  CHECK (tax_regime IS NULL OR tax_regime IN
    ('CAP. GAIN 26%', 'AGEVOLATA 12.5%', 'TFR SEPARATA', 'PIR', 'ESENTE', 'ALTRO'));

COMMENT ON COLUMN fnz_other_assets.tax_regime IS
  'Regime fiscale dell''asset. I primi cinque valori sono gli stessi del tag TASSAZIONE dei prodotti; ''TFR SEPARATA'' è la tassazione separata del TFR, che colpisce l''intero importo e non la sola plusvalenza. NULL = non indicato: nessuna imposta stimata (a differenza dei prodotti, dove il tag assente vale il 26 %).';
COMMENT ON COLUMN fnz_other_assets.cost_basis IS
  'Costo di acquisto, per calcolare la plusvalenza tassabile (valore − costo, zero se in perdita). Facoltativo: senza, i regimi che tassano la plusvalenza non stimano nessuna imposta invece di stimarla sul valore intero. Non serve a ''TFR SEPARATA'', che tassa tutto.';
