-- ═══════════════════════════════════════════════════════════════════════════
-- backup_scores(p_user) — i punteggi delle app per la relazione settimanale
-- ═══════════════════════════════════════════════════════════════════════════
--
-- La home calcola il punteggio di ogni app eseguendo `cm_apps.score_query`,
-- che è SQL scritto in tabella e parla di `auth.uid()`. Il backup gira in
-- GitHub Actions con la service key: lì `auth.uid()` è NULL — ogni conteggio
-- tornerebbe zero — e `run_score_query` rifiuta comunque chi non presenta un
-- JWT con l'email di Salvatore.
--
-- Questa funzione fa la stessa cosa per un utente passato come parametro:
-- sostituisce `auth.uid()` con quell'uuid ed esegue. Non è un secondo modo di
-- calcolare i punti — è **la stessa** query di `cm_apps`, letta da lì e non
-- ricopiata: due implementazioni dello stesso punteggio sono due punteggi
-- diversi il giorno che una delle due cambia.
--
-- ⚠️ Esegue SQL arbitrario preso da `cm_apps.score_query`, esattamente come
-- `run_score_query_unrestricted`. Per questo l'EXECUTE è **solo del
-- service_role**: dai client (anon, authenticated) non si raggiunge affatto,
-- e la guardia non è un controllo dentro la funzione ma il permesso.
--
-- ⚠️ Una query che fallisce non ferma il giro: quell'app riporta `errore` e le
-- altre restano leggibili. Una relazione che si interrompe a metà perché una
-- score_query ha una tabella rinominata è peggio di una riga che dice cos'è
-- successo.

CREATE OR REPLACE FUNCTION public.backup_scores(p_user uuid)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_app     record;
  v_sql     text;
  v_score   numeric;
  v_errore  text;
  v_out     jsonb := '[]'::jsonb;
BEGIN
  IF p_user IS NULL THEN
    RAISE EXCEPTION 'backup_scores: serve un utente';
  END IF;

  -- Una score_query che gira all'infinito non deve tenere in ostaggio il
  -- backup: dopo dieci secondi quell'app riporta l'errore e si prosegue.
  PERFORM set_config('statement_timeout', '10s', true);

  FOR v_app IN
    SELECT title,
           html_file,
           score_query,
           COALESCE(riservato, false) AS riservato
      FROM cm_apps
     WHERE COALESCE(active, false)
       AND score_query IS NOT NULL
       AND btrim(score_query) <> ''
     ORDER BY title
  LOOP
    v_sql := regexp_replace(v_app.score_query,
                            'auth\.uid\(\)',
                            quote_literal(p_user::text) || '::uuid',
                            'gi');
    v_score  := NULL;
    v_errore := NULL;

    BEGIN
      EXECUTE v_sql INTO v_score;
    EXCEPTION WHEN OTHERS THEN
      v_errore := SQLERRM;
    END;

    v_out := v_out || jsonb_build_object(
      'title',     v_app.title,
      'html_file', v_app.html_file,
      'riservato', v_app.riservato,
      'score',     v_score,
      'errore',    v_errore
    );
  END LOOP;

  RETURN v_out;
END;
$$;

REVOKE ALL ON FUNCTION public.backup_scores(uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.backup_scores(uuid) FROM anon, authenticated;
GRANT EXECUTE ON FUNCTION public.backup_scores(uuid) TO service_role;

COMMENT ON FUNCTION public.backup_scores(uuid) IS
  'Punteggi di cm_apps per un utente dato. Solo service_role: esegue lo SQL di score_query.';
