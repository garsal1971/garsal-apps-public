-- Rename Finanza tables to fnz_ prefix

-- Step 1: Rename tables (FK references update automatically)
ALTER TABLE IF EXISTS portfolios        RENAME TO fnz_portfolios;
ALTER TABLE IF EXISTS tag_categories    RENAME TO fnz_tag_categories;
ALTER TABLE IF EXISTS products          RENAME TO fnz_products;
ALTER TABLE IF EXISTS dossiers          RENAME TO fnz_dossiers;
ALTER TABLE IF EXISTS transactions      RENAME TO fnz_transactions;
ALTER TABLE IF EXISTS price_cache       RENAME TO fnz_price_cache;
ALTER TABLE IF EXISTS price_history     RENAME TO fnz_price_history;
ALTER TABLE IF EXISTS loans             RENAME TO fnz_loans;
ALTER TABLE IF EXISTS other_asset_types RENAME TO fnz_other_asset_types;
ALTER TABLE IF EXISTS other_assets      RENAME TO fnz_other_assets;
ALTER TABLE IF EXISTS dashboard_snapshots RENAME TO fnz_dashboard_snapshots;

-- Step 2: Rename indexes for consistency
ALTER INDEX IF EXISTS portfolios_user_idx             RENAME TO fnz_portfolios_user_idx;
ALTER INDEX IF EXISTS tag_categories_user_idx         RENAME TO fnz_tag_categories_user_idx;
ALTER INDEX IF EXISTS products_user_idx               RENAME TO fnz_products_user_idx;
ALTER INDEX IF EXISTS products_symbol_idx             RENAME TO fnz_products_symbol_idx;
ALTER INDEX IF EXISTS dossiers_user_idx               RENAME TO fnz_dossiers_user_idx;
ALTER INDEX IF EXISTS transactions_user_idx            RENAME TO fnz_transactions_user_idx;
ALTER INDEX IF EXISTS transactions_portfolio_idx       RENAME TO fnz_transactions_portfolio_idx;
ALTER INDEX IF EXISTS transactions_product_idx         RENAME TO fnz_transactions_product_idx;
ALTER INDEX IF EXISTS transactions_dossier_idx         RENAME TO fnz_transactions_dossier_idx;
ALTER INDEX IF EXISTS transactions_date_idx            RENAME TO fnz_transactions_date_idx;
ALTER INDEX IF EXISTS price_history_symbol_idx         RENAME TO fnz_price_history_symbol_idx;
ALTER INDEX IF EXISTS price_history_date_idx           RENAME TO fnz_price_history_date_idx;
ALTER INDEX IF EXISTS loans_user_idx                   RENAME TO fnz_loans_user_idx;
ALTER INDEX IF EXISTS other_asset_types_user_idx       RENAME TO fnz_other_asset_types_user_idx;
ALTER INDEX IF EXISTS other_assets_user_idx            RENAME TO fnz_other_assets_user_idx;
ALTER INDEX IF EXISTS idx_dashboard_snapshots_user_date RENAME TO fnz_idx_dashboard_snapshots_user_date;

-- Step 3: Recreate trigger function with updated table references
CREATE OR REPLACE FUNCTION handle_price_history_sync()
RETURNS TRIGGER AS $$
BEGIN
  INSERT INTO fnz_price_history (symbol, price, price_date)
  VALUES (NEW.symbol, NEW.price, CURRENT_DATE)
  ON CONFLICT (symbol, price_date)
  DO UPDATE SET
    price = EXCLUDED.price,
    created_at = now();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Step 4: Drop old trigger and recreate on renamed table
DROP TRIGGER IF EXISTS on_price_cache_update ON fnz_price_cache;
CREATE TRIGGER on_price_cache_update
AFTER INSERT OR UPDATE ON fnz_price_cache
FOR EACH ROW EXECUTE FUNCTION handle_price_history_sync();

-- Step 5: Drop old RLS policies (attached to old table names, now on renamed tables)
-- and recreate with fnz_ prefix for consistency
DROP POLICY IF EXISTS "portfolios_own"             ON fnz_portfolios;
DROP POLICY IF EXISTS "tag_categories_own"         ON fnz_tag_categories;
DROP POLICY IF EXISTS "products_own"               ON fnz_products;
DROP POLICY IF EXISTS "dossiers_own"               ON fnz_dossiers;
DROP POLICY IF EXISTS "transactions_own"           ON fnz_transactions;
DROP POLICY IF EXISTS "price_cache_read"           ON fnz_price_cache;
DROP POLICY IF EXISTS "price_history_read"         ON fnz_price_history;
DROP POLICY IF EXISTS "loans_own"                  ON fnz_loans;
DROP POLICY IF EXISTS "other_asset_types_own"      ON fnz_other_asset_types;
DROP POLICY IF EXISTS "other_assets_own"           ON fnz_other_assets;
DROP POLICY IF EXISTS "dashboard_snapshots_own"    ON fnz_dashboard_snapshots;

CREATE POLICY "fnz_portfolios_own"             ON fnz_portfolios             FOR ALL    USING (user_id = auth.uid());
CREATE POLICY "fnz_tag_categories_own"         ON fnz_tag_categories         FOR ALL    USING (user_id = auth.uid());
CREATE POLICY "fnz_products_own"               ON fnz_products               FOR ALL    USING (user_id = auth.uid());
CREATE POLICY "fnz_dossiers_own"               ON fnz_dossiers               FOR ALL    USING (user_id = auth.uid());
CREATE POLICY "fnz_transactions_own"           ON fnz_transactions           FOR ALL    USING (user_id = auth.uid());
CREATE POLICY "fnz_price_cache_read"           ON fnz_price_cache            FOR SELECT USING (auth.role() = 'authenticated');
CREATE POLICY "fnz_price_history_read"         ON fnz_price_history          FOR SELECT USING (auth.role() = 'authenticated');
CREATE POLICY "fnz_loans_own"                  ON fnz_loans                  FOR ALL    USING (user_id = auth.uid());
CREATE POLICY "fnz_other_asset_types_own"      ON fnz_other_asset_types      FOR ALL    USING (user_id = auth.uid());
CREATE POLICY "fnz_other_assets_own"           ON fnz_other_assets           FOR ALL    USING (user_id = auth.uid());
CREATE POLICY "fnz_dashboard_snapshots_own"    ON fnz_dashboard_snapshots    FOR ALL    USING (user_id = auth.uid());
