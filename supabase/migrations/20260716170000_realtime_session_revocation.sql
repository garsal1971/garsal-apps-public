-- Revoca sessione in tempo reale per gli ospiti (cm_guest_access).
--
-- Finora DELETE FROM auth.sessions non aveva effetto immediato: l'access
-- token già emesso è un JWT stateless, valido fino alla sua scadenza
-- naturale (~1 ora) indipendentemente dalla sessione lato server. Con
-- questa funzione, ogni verifica di accesso controlla anche che la
-- sessione del JWT corrente esista ancora in auth.sessions: cancellarla
-- blocca l'accesso dalla richiesta successiva, non tra un'ora.
CREATE OR REPLACE FUNCTION public.session_is_valid()
RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public
AS $$
  SELECT EXISTS (
    SELECT 1 FROM auth.sessions
    WHERE id = (auth.jwt() ->> 'session_id')::uuid
  )
$$;

-- has_page_access (usata dalle policy guest_read_* su fnz_loans,
-- fnz_other_assets, cntrs_transactions/categories/saldi) ora richiede
-- anche una sessione ancora attiva.
CREATE OR REPLACE FUNCTION public.has_page_access(p_page text)
RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public
AS $$
  SELECT public.session_is_valid()
     AND EXISTS (
    SELECT 1 FROM cm_guest_access
    WHERE lower(email) = lower(public.jwt_email())
      AND page = p_page
  )
$$;

-- La lettura diretta di cm_guest_access (usata da checkPageAccess() in
-- situazione-rosa.html per decidere se mostrare la dashboard) deve
-- rispettare la stessa regola, altrimenti l'app mostrerebbe comunque la
-- shell prima che le query dati falliscano.
DROP POLICY IF EXISTS "cm_guest_access_own_select" ON cm_guest_access;
CREATE POLICY "cm_guest_access_own_select" ON cm_guest_access
  FOR SELECT USING (lower(email) = lower(public.jwt_email()) AND public.session_is_valid());
