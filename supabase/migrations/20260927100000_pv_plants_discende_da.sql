-- ============================================================
-- Piante: «🌿 Discende da» — la pianta madre (una talea, un pollone, un seme)
--
-- ⚠️ ON DELETE SET NULL e non CASCADE: cancellando la madre la figlia resta,
-- e perde soltanto il collegamento. Una pianta viva non sparisce perché è
-- sparita quella da cui è nata.
--
-- ⚠️ Il CHECK impedisce solo il caso diretto (madre di sé stessa); i giri più
-- lunghi (A figlia di B figlia di A) li tiene fuori la tendina, che non offre
-- né la pianta stessa né le sue discendenti.
-- ============================================================
ALTER TABLE pv_plants ADD COLUMN IF NOT EXISTS parent_id uuid REFERENCES pv_plants(id) ON DELETE SET NULL;

DO $$ BEGIN
  ALTER TABLE pv_plants ADD CONSTRAINT pv_plants_parent_not_self CHECK (parent_id IS NULL OR parent_id <> id);
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

CREATE INDEX IF NOT EXISTS pv_plants_parent_idx ON pv_plants (parent_id) WHERE parent_id IS NOT NULL;
