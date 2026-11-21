-- Revoca dell'EXECUTE alle funzioni interne rimaste raggiungibili con la anon key.
--
-- Le migration che le hanno create facevano REVOKE ... FROM PUBLIC (o, per
-- sos_user_by_token, FROM anon, authenticated). Su Supabase però anon e
-- authenticated ricevono l'EXECUTE anche DIRETTAMENTE, dai default privileges
-- dello schema public: togliendolo a PUBLIC soltanto, il permesso resta.
-- Verificato il 21/11/2026 con has_function_privilege('anon', …) = true su tutte
-- e nove.
--
-- Il rischio era concreto: le _core prendono p_user come parametro, quindi
-- bastava l'id di un'azione e di un utente per completarla/saltarla dalla
-- anon key; amz__situazione dava i dati di Scorte Amazon di un utente qualsiasi.
--
-- Le chiamano solo il service role (notification-action) e altre funzioni
-- SECURITY DEFINER, che girano come proprietario: a loro il permesso non serve
-- da anon/authenticated. Il service role si rinomina esplicitamente.

DO $$
DECLARE
  f text;
BEGIN
  FOREACH f IN ARRAY ARRAY[
    'public.ob_action_complete_core(uuid, uuid, date)',
    'public.ob_action_skip_core(uuid, uuid, integer)',
    'public.ob__sposta_promemoria(uuid, uuid, timestamptz)',
    'public.pv_action_complete_core(uuid, uuid, date)',
    'public.pv_action_skip_core(uuid, uuid, integer)',
    'public.pv__sposta_promemoria(uuid, uuid, timestamptz)',
    'public.amz__situazione(uuid, date)',
    'public.amz__utente(text)',
    'public.sos_user_by_token(text)'
  ] LOOP
    IF to_regprocedure(f) IS NULL THEN
      RAISE NOTICE 'funzione assente, salto: %', f;
      CONTINUE;
    END IF;
    EXECUTE format('REVOKE ALL ON FUNCTION %s FROM PUBLIC, anon, authenticated', f);
    EXECUTE format('GRANT EXECUTE ON FUNCTION %s TO service_role', f);
  END LOOP;
END $$;
