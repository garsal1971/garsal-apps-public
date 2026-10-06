-- =====================================================================
-- Abituati — quanto costa riprendere un'abitudine interrotta
--
-- INTERROMPI mette `hb_habits.status` a `stopped`: la riga resta con tutte
-- le sue spunte, ma esce dalla riconciliazione — da lì in poi non genera
-- `missed`, non consuma jolly e non può né vincere né fallire.
--
-- RIPRENDI la rimette `active`, ed è lì che c'è la trappola: se
-- `started_at` restasse quello di partenza, il primo giro di
-- riconciliazione marcherebbe `missed` ogni giorno passato
-- dall'interruzione — `checkMissedDays` e `hb_reconcile` guardano da
-- `started_at` a ieri — i jolly finirebbero sul posto e il game over
-- scatterebbe prima ancora di rivedere la scheda. Per questo riprendere
-- chiede **da quale data** ripartire, e prima di scrivere dice quanto
-- costerebbe quella scelta.
--
-- Quel conto è questa funzione, e sta qui invece che nel client per la
-- ragione di sempre: lo fanno in due — `habit-tracker.html` e l'app
-- nativa — e due copie della stessa formula sono due avvisi diversi il
-- giorno che una delle due cambia. È la stessa scelta di `hb_streak` e
-- `hb_fallimenti`.
--
-- Ricalca il conteggio di `checkMissedDays` / `hb_reconcile`, comprese le
-- due cose che lo rendono giusto:
--   • conta i **giorni**, non le sessioni: tre slot saltati lo stesso
--     giorno costano un jolly solo;
--   • conta come da recuperare sia il giorno senza nessuna riga (che
--     diventerebbe `missed`) sia quello già segnato come fallito — per i
--     jolly valgono uguale.
-- =====================================================================

CREATE OR REPLACE FUNCTION public.hb_giorni_da_recuperare(
  p_habit_id uuid,
  p_da       date,
  p_oggi     date DEFAULT CURRENT_DATE
)
RETURNS integer
-- Sola lettura: SECURITY INVOKER come `hb_giorni_fatti`, così la RLS su
-- `hb_completions` resta in mezzo.
LANGUAGE plpgsql STABLE SECURITY INVOKER SET search_path = public
AS $$
DECLARE
  v_h      hb_habits%ROWTYPE;
  v_oggi   date := COALESCE(p_oggi, CURRENT_DATE);
  v_giorno date;
  v_giorni text[];
  v_dovuto boolean;
  v_conta  integer := 0;
BEGIN
  IF p_da IS NULL THEN RETURN 0; END IF;

  SELECT * INTO v_h FROM hb_habits WHERE id = p_habit_id;
  IF NOT FOUND THEN RETURN 0; END IF;

  v_giorni := hb_lista(to_jsonb(v_h) -> 'weekdays');
  v_giorno := p_da;

  -- Da `p_da` a **ieri**: oggi è ancora in tempo, e infatti né
  -- `checkMissedDays` né `hb_reconcile` lo guardano.
  WHILE v_giorno < v_oggi LOOP
    v_dovuto := CASE
      WHEN v_h.frequency IN ('daily', 'daily_multiple') THEN true
      -- I giorni sono numerati alla JavaScript: 0 = domenica.
      WHEN v_h.frequency = 'weekly' THEN
        (EXTRACT(dow FROM v_giorno)::int)::text = ANY (v_giorni)
      ELSE false
    END;

    IF v_dovuto AND NOT hb_giorno_fatto(p_habit_id, v_giorno) THEN
      v_conta := v_conta + 1;
    END IF;

    v_giorno := v_giorno + 1;
  END LOOP;

  RETURN v_conta;
END;
$$;

COMMENT ON FUNCTION public.hb_giorni_da_recuperare(uuid, date, date) IS
  'Quanti jolly costerebbe riprendere l''abitudine da p_da: un giorno dovuto senza spunta completata vale uno, comunque siano gli slot.';

GRANT EXECUTE ON FUNCTION public.hb_giorni_da_recuperare(uuid, date, date) TO authenticated;
