-- Toglie le pesate del 13 ottobre 2026 dell'utente che ha l'obiettivo attivo sulla massa
-- grassa, per riprovare da capo la pesata che conta (v4.15.1: il ❓ sulla «Prossima»).
-- Una tantum: nessun dato personale, solo la data.
--
-- ⚠️ user_id su ps_weight_tracking e ps_objectives può non esserci (tabelle nate a mano): si
-- legge da to_jsonb, come in ps_ema_ricalcola. Senza colonna valgono tutte le righe di quel giorno.
-- ⚠️ Una nuova sincronizzazione da Health Connect (Renpho) le rileggerebbe.

DO $$
DECLARE
  v_user uuid;
  v_tolte int;
BEGIN
  SELECT NULLIF(to_jsonb(o)->>'user_id', '')::uuid INTO v_user
    FROM ps_objectives o
   WHERE o.status = 'active' AND o.use_fat_mass
   LIMIT 1;

  DELETE FROM ps_weight_tracking t
   WHERE (to_jsonb(t)->>'date') = '2026-10-13'
     AND (v_user IS NULL OR to_jsonb(t)->>'user_id' IS NULL OR to_jsonb(t)->>'user_id' = v_user::text);
  GET DIAGNOSTICS v_tolte = ROW_COUNT;
  RAISE NOTICE 'ps_weight_tracking: tolte % pesate del 2026-10-13', v_tolte;

  -- Il trigger delle medie qui non sa chi è l'utente (auth.uid() è NULL): le si rifà a mano.
  IF v_user IS NOT NULL THEN
    BEGIN
      PERFORM ps_ema_ricalcola(v_user);
    EXCEPTION WHEN OTHERS THEN
      RAISE WARNING 'ps_ema_ricalcola: %', SQLERRM;
    END;
  END IF;
END $$;
