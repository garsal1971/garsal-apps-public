-- Converte reminder_presets da text[] a jsonb, solo se ancora text[].
-- In produzione la colonna era già jsonb: questo DO-block è idempotente.
DO $$ BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = 'public'
      AND table_name   = 'cm_notification_rules'
      AND column_name  = 'reminder_presets'
      AND data_type    = 'ARRAY'
  ) THEN
    ALTER TABLE cm_notification_rules
      ALTER COLUMN reminder_presets TYPE jsonb
      USING CASE
        WHEN reminder_presets IS NULL                        THEN NULL
        WHEN array_length(reminder_presets, 1) IS NULL      THEN NULL
        WHEN array_length(reminder_presets, 1) = 1          THEN (reminder_presets[1])::jsonb
        ELSE to_jsonb(reminder_presets)
      END;
  END IF;
END $$;
