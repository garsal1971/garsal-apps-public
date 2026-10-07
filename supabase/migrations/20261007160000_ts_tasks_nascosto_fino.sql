-- ============================================================================
-- Tasks: «🙈 Nascondi» un task scaduto per qualche giorno.
--
-- nascosto_fino è l'ULTIMO giorno (incluso) in cui il task non compare fra
-- gli ⚠️ SCADUTI della panoramica: in quei giorni sta nella sezione 🙈 NASCOSTI,
-- e dal giorno dopo torna fra gli scaduti da sé. NULL = visibile.
--
-- È un filtro di lettura e basta: scadenza, prossima occorrenza, regole e
-- promemoria NON si toccano, e nessuna RPC del ciclo di vita la legge.
-- Nessun DEFAULT oltre a NULL e nessun backfill: nessun task nasce nascosto.
-- ============================================================================

ALTER TABLE public.ts_tasks
    ADD COLUMN IF NOT EXISTS nascosto_fino date;

COMMENT ON COLUMN public.ts_tasks.nascosto_fino IS
    'Ultimo giorno (incluso) in cui il task è tolto dagli SCADUTI della panoramica. NULL = visibile. Non tocca scadenze né promemoria.';
