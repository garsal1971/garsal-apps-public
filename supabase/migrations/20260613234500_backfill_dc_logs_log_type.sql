-- Backfill log_type='review' e pulisce il prefisso [RIVEDI] dalle note
-- per i log inseriti dalla v1.9.9 prima dell'aggiunta della colonna log_type.
UPDATE dc_logs
SET
    log_type = 'review',
    notes    = TRIM(SUBSTR(notes, LENGTH('[RIVEDI]') + 1))
WHERE notes LIKE '[RIVEDI]%';
