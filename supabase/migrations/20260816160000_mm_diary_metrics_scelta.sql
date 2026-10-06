-- ============================================================
-- MEMORANDUM — misure a scelta (combo configurabili)
--
-- Una quarta specie di misura accanto a scala, numero e sì/no:
-- un elenco di opzioni definite sulla misura, di cui la
-- registrazione ne archivia una.
--
-- Le opzioni stanno in `options` come [{id, label}] e la
-- registrazione archivia l'**id**, non l'etichetta: rinominare
-- un'opzione deve rileggere lo storico col nome nuovo, non
-- spaccarlo in due categorie che sembrano diverse. È la stessa
-- ragione per cui le misure si aggiornano riga per riga invece
-- di essere ricreate.
-- ============================================================

DO $$
BEGIN
  IF to_regclass('public.mm_diary_metrics') IS NULL THEN
    RAISE NOTICE 'mm_diary_metrics non esiste: migration saltata (le tabelle mm_* nascono dal SQL in memo.html → Impostazioni).';
    RETURN;
  END IF;

  ALTER TABLE mm_diary_metrics
    ADD COLUMN IF NOT EXISTS options jsonb NOT NULL DEFAULT '[]'::jsonb;

  -- il CHECK su kind va rifatto da capo: 'scelta' non era previsto
  ALTER TABLE mm_diary_metrics DROP CONSTRAINT IF EXISTS mm_diary_metrics_kind_check;
  ALTER TABLE mm_diary_metrics ADD CONSTRAINT mm_diary_metrics_kind_check
    CHECK (kind IN ('scala', 'numero', 'bool', 'scelta'));

  -- una combo senza opzioni non chiederebbe niente: è una misura rotta,
  -- non una misura vuota
  ALTER TABLE mm_diary_metrics DROP CONSTRAINT IF EXISTS mm_diary_metrics_scelta_check;
  ALTER TABLE mm_diary_metrics ADD CONSTRAINT mm_diary_metrics_scelta_check
    CHECK (
      kind <> 'scelta'
      OR (jsonb_typeof(options) = 'array' AND jsonb_array_length(options) > 0)
    );
END $$;
