-- Aggiunge colonna log_type a dc_logs per distinguere risposte normali da rivalutazioni
ALTER TABLE dc_logs ADD COLUMN IF NOT EXISTS log_type TEXT DEFAULT 'response';
