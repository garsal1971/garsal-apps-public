-- ============================================================
-- MEMORANDUM — la nota di una misura e il titolo di una
-- registrazione
--
-- `hint`  — cosa vuol dire il punteggio di questa misura
--           («1 = per niente, 20 = non riesco a pensare ad
--           altro»). Sta sulla misura e viene rimostrato sotto
--           il suo nome a ogni registrazione: è lì che serve,
--           nel momento in cui si dà il voto.
--
-- `title` — il titolo breve della registrazione, che l'app
--           chiede sempre. Sul database resta NOT NULL DEFAULT
--           '': le registrazioni fatte prima di questa colonna
--           un titolo non ce l'hanno, e un vincolo che le
--           rifiutasse renderebbe impossibile perfino
--           correggerle.
-- ============================================================

DO $$
BEGIN
  IF to_regclass('public.mm_diary_metrics') IS NOT NULL THEN
    ALTER TABLE mm_diary_metrics
      ADD COLUMN IF NOT EXISTS hint text NOT NULL DEFAULT '';
  ELSE
    RAISE NOTICE 'mm_diary_metrics non esiste: salto la colonna hint (le tabelle mm_* nascono dal SQL in memo.html → Impostazioni).';
  END IF;

  IF to_regclass('public.mm_diary_entries') IS NOT NULL THEN
    ALTER TABLE mm_diary_entries
      ADD COLUMN IF NOT EXISTS title text NOT NULL DEFAULT '';
  ELSE
    RAISE NOTICE 'mm_diary_entries non esiste: salto la colonna title.';
  END IF;
END $$;
