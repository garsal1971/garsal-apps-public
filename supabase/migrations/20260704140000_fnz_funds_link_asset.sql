-- Collega un fondo a un portafoglio o a un altro asset, e definisce il
-- partecipante di riferimento la cui quota va sincronizzata sul campo
-- ownership_percentage del portafoglio/asset collegato.
ALTER TABLE fnz_funds
  ADD COLUMN IF NOT EXISTS linked_portfolio_id   uuid REFERENCES fnz_portfolios(id)   ON DELETE SET NULL,
  ADD COLUMN IF NOT EXISTS linked_other_asset_id uuid REFERENCES fnz_other_assets(id) ON DELETE SET NULL,
  ADD COLUMN IF NOT EXISTS owner_participant     text;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'fnz_funds_single_link'
  ) THEN
    ALTER TABLE fnz_funds
      ADD CONSTRAINT fnz_funds_single_link
      CHECK (linked_portfolio_id IS NULL OR linked_other_asset_id IS NULL);
  END IF;
END $$;

CREATE INDEX IF NOT EXISTS fnz_funds_linked_portfolio_idx   ON fnz_funds(linked_portfolio_id);
CREATE INDEX IF NOT EXISTS fnz_funds_linked_other_asset_idx ON fnz_funds(linked_other_asset_id);
