-- Flag per mostrare un avviso nella home AppSphere
ALTER TABLE dc_decisions ADD COLUMN IF NOT EXISTS show_home_alert BOOLEAN DEFAULT FALSE;

-- Aggiorna score_query di DecisionKeeper: somma reale dei punti dai log
UPDATE cm_apps
SET score_query = 'SELECT GREATEST(0, COALESCE(SUM(l.points_earned)::INTEGER, 0)) FROM dc_logs l JOIN dc_decisions d ON d.id = l.decision_id WHERE d.user_id = auth.uid()'
WHERE html_file = 'decisions.html';
