-- Piante: i punti delle azioni sono un punteggio vero (fatta +10, saltata −2, in ritardo −2),
-- quindi la bolla li deve scrivere e devono entrare nel totale che paga i premi.
-- La migration che ha creato la riga (20260926100000_pv_piante.sql) contava sul DEFAULT true
-- di conta_punti, ma se la riga esisteva già non l'ha toccata: qui lo si scrive esplicito.
-- Idempotente: se è già true non cambia niente.
UPDATE cm_apps
   SET conta_punti = true
 WHERE html_file = 'piante.html'
   AND conta_punti IS DISTINCT FROM true;
