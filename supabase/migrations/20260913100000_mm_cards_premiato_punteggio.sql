-- ============================================================
-- MEMO — 🏅 Premiati: il quinto tipo di scheda, e i suoi punti
-- ============================================================
-- Una scheda `premiato` è una nota più un numero: `mm_cards.punteggio`.
-- La somma di quei numeri è il numero della bolla di Memo in AppSphere e
-- si aggiunge al totale che paga i premi — cioè da oggi Memo esce da
-- APP_SENZA_PUNTI (index.html, home/PortedApps.kt, scripts/backup-report.mjs:
-- se divergono, le due home e la relazione settimanale mostrano tre
-- totali diversi).
--
-- ⚠️ Il punteggio è `NOT NULL DEFAULT 0` e sta su `mm_cards`, non su una
-- tabella a parte: è un numero per scheda, e una riga in più da leggere a
-- ogni apertura dell'elenco sarebbe una join per un intero. Sugli altri
-- quattro tipi vale 0 e la pagina lo riscrive comunque a ogni salvataggio,
-- o una scheda che cambiasse tipo si porterebbe dietro punti che nessuno
-- vede più.
--
-- ⚠️ Il CHECK su `kind` va rifatto da capo: 'premiato' non era previsto.
-- È la stessa mossa di 20260902100000 per 'link'.

DO $$
BEGIN
  IF to_regclass('public.mm_cards') IS NULL THEN
    RAISE NOTICE 'mm_cards non esiste: migration saltata (le tabelle mm_* nascono dal SQL in memo.html → Impostazioni).';
    RETURN;
  END IF;

  ALTER TABLE mm_cards ADD COLUMN IF NOT EXISTS punteggio integer NOT NULL DEFAULT 0;

  ALTER TABLE mm_cards DROP CONSTRAINT IF EXISTS mm_cards_kind_check;
  ALTER TABLE mm_cards ADD CONSTRAINT mm_cards_kind_check
    CHECK (kind IN ('nota', 'lista', 'diario', 'link', 'premiato'));
END $$;

-- ── La bolla: da «quante schede» a «quanti punti» ────────────
-- ⚠️ La riga di `cm_apps` per memo.html non nasce da nessuna migration
-- (è stata scritta a mano in produzione), quindi qui si aggiorna e non si
-- inserisce: su un database che quella riga non ce l'ha, l'UPDATE non
-- tocca niente e non fallisce.
-- ⚠️ Il COALESCE non è prudenza generica: senza nessun premiato la SUM
-- torna NULL, e `run_score_query` si aspetta un intero — la bolla
-- sparirebbe invece di restare al minimo.
DO $$
BEGIN
  IF to_regclass('public.cm_apps') IS NULL THEN
    RAISE NOTICE 'cm_apps non esiste: score_query di memo.html non aggiornata.';
    RETURN;
  END IF;

  UPDATE cm_apps
     SET score_query = 'SELECT COALESCE(SUM(punteggio), 0)::int FROM mm_cards WHERE user_id = auth.uid() AND kind = ''premiato'''
   WHERE html_file = 'memo.html';
END $$;
