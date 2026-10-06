-- ============================================================
-- Spuntiamola — registrazione nel launcher AppSphere
-- ============================================================
-- L'app conserva periodo e spunte in localStorage: nessuna tabella
-- dedicata, quindi lo score è costante 0 (bolla di dimensione minima,
-- nessun badge numerico nel launcher).

INSERT INTO cm_apps (title, description, score_query, color, active, html_file, riservato)
SELECT 'Spuntiamola',
       'Spunta i giorni che mancano al traguardo',
       'SELECT 0::int',
       '#00B894', true, 'spuntiamola.html', false
WHERE NOT EXISTS (SELECT 1 FROM cm_apps WHERE title = 'Spuntiamola');
