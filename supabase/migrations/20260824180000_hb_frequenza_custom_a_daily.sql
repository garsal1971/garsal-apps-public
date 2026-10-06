-- =====================================================================
-- Abituati — «Personalizzata» non è mai esistita: le righe tornano daily
--
-- `hb_habits.frequency` ammetteva anche 'custom', che la tendina di
-- `habit-tracker.html` offriva ma **nessuna riga di codice leggeva**: la
-- stringa compariva una volta sola, nell'<option>. Un'abitudine così cadeva
-- nel ramo `else` di ogni funzione — cioè si comportava come una giornaliera —
-- tranne che nel controllo dei giorni mancati (`checkMissedDays` nel web,
-- `hb_reconcile` qui, che ha un `ELSE false` esplicito): quei giorni non
-- diventavano mai `missed`, quindi nessun jolly veniva consumato e lo stack
-- non poteva fallire. Un'abitudine che non si può perdere.
--
-- L'opzione è stata tolta dalla tendina; questa migration riporta a 'daily'
-- le righe che l'avevano, che è **come si sono sempre comportate** ovunque
-- tranne che in quel controllo. Da qui in poi i loro giorni saltati contano
-- come per tutte le altre.
--
-- Prende anche la stringa vuota e il NULL: la tendina di modifica non ha mai
-- avuto la voce 'custom', quindi aprendo in modifica una di quelle abitudini
-- il select restava senza selezione e il salvataggio scriveva `frequency: ''`.
-- Sono la stessa anomalia con due facce.
--
-- `hb_habits` non nasce da nessuna migration (la tabella è stata creata a
-- mano prima che esistesse questa cartella), quindi si controlla che ci sia:
-- su un progetto dev appena creato non c'è, e la migration deve saltare
-- invece di far fallire tutto il deploy.
-- =====================================================================

DO $$
DECLARE
  v_righe integer;
BEGIN
  IF to_regclass('public.hb_habits') IS NULL THEN
    RAISE NOTICE 'hb_habits non esiste: nessuna riga da riportare a daily';
    RETURN;
  END IF;

  UPDATE hb_habits
     SET frequency = 'daily'
   WHERE frequency IS NULL
      OR frequency NOT IN ('daily', 'daily_multiple', 'weekly');

  GET DIAGNOSTICS v_righe = ROW_COUNT;
  RAISE NOTICE 'abitudini riportate a daily: %', v_righe;
END $$;
