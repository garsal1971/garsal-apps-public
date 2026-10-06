-- ============================================================================
-- pv_ai_answers.provider — quale IA ha dato la risposta
-- ============================================================================
-- «Chiedi all'IA» delle Piante sceglie fra Gemini, Qwen e Claude (tendina
-- nell'app), e accanto a ogni risposta si scrive chi l'ha data.
--
-- ⚠️ `provider` e `model` sono due cose diverse: il primo è il fornitore
-- ('gemini' | 'qwen' | 'claude'), il secondo il modello preciso che ha
-- risposto (qwen-plus o qwen-vl-max, a seconda che ci fossero foto).
--
-- ⚠️ Nessun backfill: le risposte nate prima restano NULL, e l'app le legge
-- come Claude — che è quello che sono, perché fino a qui c'era solo lui.
-- Nessun CHECK sui valori, come `item_key` e `kind`: un fornitore nuovo è una
-- riga nella Edge Function, non una migration.
-- ============================================================================

ALTER TABLE pv_ai_answers ADD COLUMN IF NOT EXISTS provider text;
