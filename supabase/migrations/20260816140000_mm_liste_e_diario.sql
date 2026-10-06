-- ============================================================
-- MEMORANDUM — liste e diario quantitativo
--
-- Le tabelle mm_* nascono dal SQL mostrato in memo.html →
-- Impostazioni, quindi questa migration è scritta per girare
-- anche dove quelle tabelle esistono già (IF NOT EXISTS /
-- ADD COLUMN IF NOT EXISTS) e per non fallire su un database
-- nuovo dove non esistono ancora — in quel caso il blocco DO
-- salta tutto e ci pensa il SQL della pagina.
-- ============================================================

DO $$
BEGIN
  IF to_regclass('public.mm_cards') IS NULL THEN
    RAISE NOTICE 'mm_cards non esiste: migration saltata (le tabelle mm_* nascono dal SQL in memo.html → Impostazioni).';
    RETURN;
  END IF;

  -- ----------------------------------------------------------
  -- 1. Il tipo di scheda
  --    'nota' è il default: tutte le schede che esistono oggi
  --    restano quello che sono senza toccarle una per una.
  -- ----------------------------------------------------------
  ALTER TABLE mm_cards ADD COLUMN IF NOT EXISTS kind text NOT NULL DEFAULT 'nota';

  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'mm_cards_kind_check'
  ) THEN
    ALTER TABLE mm_cards ADD CONSTRAINT mm_cards_kind_check
      CHECK (kind IN ('nota', 'lista', 'diario'));
  END IF;

  -- ----------------------------------------------------------
  -- 2. Voci di una lista
  -- ----------------------------------------------------------
  CREATE TABLE IF NOT EXISTS mm_list_items (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    card_id    uuid NOT NULL REFERENCES mm_cards(id) ON DELETE CASCADE,
    user_id    uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    text       text NOT NULL DEFAULT '',
    done       boolean NOT NULL DEFAULT false,
    position   integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    done_at    timestamptz
  );
  ALTER TABLE mm_list_items ENABLE ROW LEVEL SECURITY;
  CREATE INDEX IF NOT EXISTS mm_list_items_card ON mm_list_items(card_id, position);

  -- ----------------------------------------------------------
  -- 3. Le misure di un diario
  --    kind = 'scala'  → min_value/max_value obbligatori (1-20, 1-100…)
  --    kind = 'numero' → nessun limite, unit è l'unità di misura
  --    kind = 'bool'   → sì/no, vale 0 o 1 nei riepiloghi
  -- ----------------------------------------------------------
  CREATE TABLE IF NOT EXISTS mm_diary_metrics (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    card_id    uuid NOT NULL REFERENCES mm_cards(id) ON DELETE CASCADE,
    user_id    uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    name       text NOT NULL,
    kind       text NOT NULL DEFAULT 'scala',
    min_value  numeric,
    max_value  numeric,
    unit       text NOT NULL DEFAULT '',
    position   integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT mm_diary_metrics_kind_check CHECK (kind IN ('scala', 'numero', 'bool')),
    CONSTRAINT mm_diary_metrics_scala_check CHECK (
      kind <> 'scala' OR (min_value IS NOT NULL AND max_value IS NOT NULL AND max_value > min_value)
    )
  );
  ALTER TABLE mm_diary_metrics ENABLE ROW LEVEL SECURITY;
  CREATE INDEX IF NOT EXISTS mm_diary_metrics_card ON mm_diary_metrics(card_id, position);

  -- ----------------------------------------------------------
  -- 4. Le registrazioni
  --    measures è { "<id della misura>": numero|booleano }: le
  --    misure sono righe vere (una registrazione passata deve
  --    restare leggibile), i valori no — sono un pugno di numeri
  --    che si leggono e si scrivono sempre tutti insieme.
  -- ----------------------------------------------------------
  CREATE TABLE IF NOT EXISTS mm_diary_entries (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    card_id    uuid NOT NULL REFERENCES mm_cards(id) ON DELETE CASCADE,
    user_id    uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    entry_date date NOT NULL DEFAULT current_date,
    note       text NOT NULL DEFAULT '',
    measures   jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
  );
  ALTER TABLE mm_diary_entries ENABLE ROW LEVEL SECURITY;
  CREATE INDEX IF NOT EXISTS mm_diary_entries_card
    ON mm_diary_entries(card_id, entry_date DESC);
END $$;

-- ------------------------------------------------------------
-- Policy: fuori dal blocco DO perché CREATE POLICY non ammette
-- IF NOT EXISTS e va quindi tolta e rifatta.
-- ------------------------------------------------------------
DO $$
BEGIN
  IF to_regclass('public.mm_list_items') IS NOT NULL THEN
    DROP POLICY IF EXISTS "mm_list_items: utente vede solo le sue" ON mm_list_items;
    CREATE POLICY "mm_list_items: utente vede solo le sue" ON mm_list_items
      FOR ALL USING (auth.uid() = user_id) WITH CHECK (auth.uid() = user_id);
  END IF;

  IF to_regclass('public.mm_diary_metrics') IS NOT NULL THEN
    DROP POLICY IF EXISTS "mm_diary_metrics: utente vede solo le sue" ON mm_diary_metrics;
    CREATE POLICY "mm_diary_metrics: utente vede solo le sue" ON mm_diary_metrics
      FOR ALL USING (auth.uid() = user_id) WITH CHECK (auth.uid() = user_id);
  END IF;

  IF to_regclass('public.mm_diary_entries') IS NOT NULL THEN
    DROP POLICY IF EXISTS "mm_diary_entries: utente vede solo le sue" ON mm_diary_entries;
    CREATE POLICY "mm_diary_entries: utente vede solo le sue" ON mm_diary_entries
      FOR ALL USING (auth.uid() = user_id) WITH CHECK (auth.uid() = user_id);
  END IF;
END $$;
