-- "Analisi Costi" deve comparire sempre nel launcher AppSphere, non solo in modalità nascosta.
UPDATE cm_apps SET riservato = false WHERE title = 'Analisi Costi';
