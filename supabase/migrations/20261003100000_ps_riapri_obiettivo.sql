-- ↩️ Riaprire un obiettivo di peso chiuso (successo o fallito).
--
-- È il contrario di `ps_chiudi_obiettivo`, e come lei vive nel database perché la chiamano
-- tutt'e due le implementazioni (weight-quest.html e l'APK nativa): due copie della stessa
-- regola sarebbero due riaperture diverse il giorno che una delle due cambia.
--
-- Cosa fa: `status` torna 'active' e `total_score` torna 0 — i punti incassati alla chiusura
-- escono dal totale che paga i premi. Non tocca nient'altro: pesate, traguardi, premi grattati
-- e punti dei traguardi restano dove sono, e richiudendo il conto si rifà da capo.
--
-- ⚠️ Rifiuta se c'è già un altro obiettivo attivo: il diario delle calorie legge l'obiettivo
-- attivo più recente, e con due attivi parlerebbe di quello sbagliato senza dirlo.
--
-- ⚠️ I promemoria della pesata li ricrea il client (syncPromemoriaPesata /
-- PesoRepository.sincronizzaPromemoria), che è l'unico posto in cui si scrivono.
--
-- SECURITY INVOKER: la RLS di ps_objectives resta in mezzo, quindi «un altro attivo» vuol dire
-- un altro obiettivo DI CHI CHIAMA.

CREATE OR REPLACE FUNCTION public.ps_riapri_obiettivo(p_objective_id text)
RETURNS jsonb
LANGUAGE plpgsql SECURITY INVOKER SET search_path = public
AS $$
DECLARE
  o jsonb;
  v_altro text;
  v_n int;
BEGIN
  SELECT to_jsonb(x) INTO o FROM ps_objectives x WHERE x.id::text = p_objective_id;
  IF o IS NULL THEN
    RETURN jsonb_build_object('ok', false, 'error', 'Obiettivo non trovato.');
  END IF;
  IF COALESCE(o->>'status', '') NOT IN ('success', 'failed') THEN
    RETURN jsonb_build_object('ok', false, 'error', 'Questo obiettivo non è chiuso.');
  END IF;

  SELECT x.objective_name INTO v_altro
    FROM ps_objectives x
   WHERE x.id::text <> p_objective_id
     AND COALESCE(x.status, 'active') NOT IN ('success', 'failed')
   LIMIT 1;
  IF FOUND THEN
    RETURN jsonb_build_object('ok', false, 'error',
      format('C''è già un obiettivo attivo («%s»): chiudilo o eliminalo prima di riaprirne un altro.',
             COALESCE(v_altro, 'senza nome')));
  END IF;

  UPDATE ps_objectives SET status = 'active', total_score = 0
   WHERE id::text = p_objective_id AND status IN ('success', 'failed');
  GET DIAGNOSTICS v_n = ROW_COUNT;
  IF v_n = 0 THEN
    RETURN jsonb_build_object('ok', false, 'error', 'Obiettivo non riaperto: nessuna riga aggiornata.');
  END IF;

  RETURN jsonb_build_object('ok', true,
    'punti_tolti', COALESCE(NULLIF(o->>'total_score', '')::numeric, 0));
END $$;

REVOKE ALL ON FUNCTION public.ps_riapri_obiettivo(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.ps_riapri_obiettivo(text) TO authenticated, service_role;
