-- =====================================================================
-- Chiusura ultimi residui emersi dall'audit completo di pg_policies
--
-- 1. cm_settings: era leggibile da QUALSIASI utente autenticato
--    (auth.uid() IS NOT NULL), inclusa Rosa. Contiene tra l'altro
--    hidden_mode_sequence, la sequenza segreta del launcher per le app
--    riservate. Ora riservata al proprietario; il launcher la legge col
--    JWT di garsal e la edge function check-hidden-sequence usa la
--    service role, quindi nessun client si rompe.
--
-- 2. hb_archived_stacks: le policy avevano una condizione tautologica
--    (auth.uid() = auth.uid(), sempre vera) — l'accesso reale dipendeva
--    solo dalla RLS di hb_habits nella subquery. Sostituite con
--    accesso esplicito al solo proprietario.
--
-- Richiede public.is_garsal() creata da 20260611160000_rosa_readonly_access.
-- =====================================================================

DROP POLICY IF EXISTS "cm_settings_select" ON cm_settings;
DROP POLICY IF EXISTS "cm_settings_garsal_only" ON cm_settings;
CREATE POLICY "cm_settings_garsal_only" ON cm_settings
  FOR ALL USING (public.is_garsal()) WITH CHECK (public.is_garsal());

DROP POLICY IF EXISTS "Users can view their own archived stacks" ON hb_archived_stacks;
DROP POLICY IF EXISTS "Users can insert their own archived stacks" ON hb_archived_stacks;
DROP POLICY IF EXISTS "Users can delete their own archived stacks" ON hb_archived_stacks;
DROP POLICY IF EXISTS "hb_archived_stacks_garsal_only" ON hb_archived_stacks;
CREATE POLICY "hb_archived_stacks_garsal_only" ON hb_archived_stacks
  FOR ALL USING (public.is_garsal()) WITH CHECK (public.is_garsal());
