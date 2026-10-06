-- Seed Finanza, Casa Rosa and Contabilità apps in cm_apps
INSERT INTO cm_apps (title, description, score_query, color, active, html_file, riservato)
SELECT 'Finanza', 'Gestione portafogli, titoli e patrimonio netto', 'SELECT COUNT(*)::int FROM fnz_transactions WHERE user_id = auth.uid()', '#4f46e5', true, 'finanza.html', true
WHERE NOT EXISTS (SELECT 1 FROM cm_apps WHERE title = 'Finanza');

INSERT INTO cm_apps (title, description, score_query, color, active, html_file, riservato)
SELECT 'Casa Rosa', 'Situazione cassa e pagamenti Casa Rosa', 'SELECT COUNT(*)::int FROM cntrs_transactions WHERE user_id = auth.uid()', '#db2777', true, 'casarosa.html', true
WHERE NOT EXISTS (SELECT 1 FROM cm_apps WHERE title = 'Casa Rosa');

INSERT INTO cm_apps (title, description, score_query, color, active, html_file, riservato)
SELECT 'Contabilità', 'Gestione conto cointestato e report annuali', 'SELECT COUNT(*)::int FROM acct_transactions WHERE user_id = auth.uid()', '#0891b2', true, 'contabilita.html', true
WHERE NOT EXISTS (SELECT 1 FROM cm_apps WHERE title = 'Contabilità');
