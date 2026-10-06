-- ============================================================
-- MEMORANDUM — schede riservate
--
-- Stessa colonna e stessa semantica di `el_groups.riservato` e
-- `cm_apps.riservato`: fuori dalla modalità nascosta la riga non
-- viene proprio letta, e il filtro sta nella query, non nel
-- rendering — una scheda riservata che arrivasse al client e
-- venisse solo nascosta a schermo sarebbe visibile a chiunque
-- apra gli strumenti del browser.
--
-- DEFAULT false: le schede che esistono già restano quello che
-- erano, cioè normali.
-- ============================================================

DO $$
BEGIN
  IF to_regclass('public.mm_cards') IS NULL THEN
    RAISE NOTICE 'mm_cards non esiste: migration saltata (le tabelle mm_* nascono dal SQL in memo.html → Impostazioni).';
    RETURN;
  END IF;

  ALTER TABLE mm_cards
    ADD COLUMN IF NOT EXISTS riservato boolean NOT NULL DEFAULT false;

  -- l'elenco filtra sempre per utente e quasi sempre per riservato
  CREATE INDEX IF NOT EXISTS mm_cards_user_riservato
    ON mm_cards(user_id, riservato);
END $$;
