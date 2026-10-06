-- =====================================================================
-- Abituati: la bolla non conta più le spunte degli stack in corso
--
-- La score_query di habit-tracker.html sommava anche, per ogni abitudine
-- attiva, (spunte fatte / goal) × points_reward: un premio pagato a rate
-- prima che lo stack fosse vinto. Da qui in poi i punti arrivano solo a
-- stack chiuso — completato, fallito o archiviato.
--
-- E il premio di uno stack completato si contava DUE volte: hb_reconcile
-- mette l'abitudine a status = 'completed' E scrive la riga d'archivio con
-- points_earned = points_reward. Resta la sola riga d'archivio.
--
-- La query è quella che c'era in produzione (letta dal pannello ⚙️ delle
-- badge query) meno quei due addendi. Bolle, totale che paga i
-- premi, home nativa e relazione del backup la leggono tutti da qui.
-- ⚠️ Il lettore di ☰ → 🏆 Punti in index.html (PT_LETTORI) va tenuto
-- allineato: rilegge le stesse righe che questa query somma.
-- =====================================================================
DO $$
BEGIN
  IF to_regclass('public.cm_apps') IS NULL THEN
    RAISE NOTICE 'cm_apps non esiste: score_query di habit-tracker.html non aggiornata.';
    RETURN;
  END IF;

  UPDATE cm_apps
     SET score_query = 'SELECT ('
       || ' - COALESCE((SELECT SUM(points_penalty) FROM hb_habits WHERE status = ''failed''), 0)'
       || ' + COALESCE((SELECT SUM(points_earned) FROM hb_archived_stacks), 0)'
       || ')::int'
   WHERE html_file = 'habit-tracker.html';
END $$;
