-- Le transazioni storiche (2017-2025) importate con dati_migrazione_auto.sql sono state
-- inserite con user_id NULL. La RLS policy "acct_transactions_own" (user_id = auth.uid())
-- non fa mai match su NULL, quindi quelle righe sono invisibili in contabilita.html:
-- il combo anno mostra solo il 2026 (unico anno con user_id valorizzato).
UPDATE acct_transactions
SET user_id = '94560122-d87e-4604-aa41-2a1292ea7b64'
WHERE user_id IS NULL;
