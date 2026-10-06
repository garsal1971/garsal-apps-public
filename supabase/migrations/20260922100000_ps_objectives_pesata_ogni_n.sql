-- «Ti pisasti?» — ogni quanti giorni ci si pesa, e il promemoria della pesata.
--
-- weigh_every_days  NULL = la regola di sempre: ogni giorno fa punti e i giorni senza pesata
--                   si ricostruiscono. Con N i punti si danno solo nei giorni fissati
--                   (inizio, inizio+N, …): bonus/malus sul peso, malus se il giorno è passato
--                   senza pesata, zero negli altri giorni. Nullable di proposito: gli obiettivi
--                   già in archivio restano esattamente come erano.
-- reminder_enabled  la spunta «ricordamelo»; le regole vere stanno in cm_notification_rules
--                   (app = 'weight'), una per canale, e questa colonna serve a riaprire il form.
-- reminder_time     ora locale (Europe/Rome) della notifica.
-- reminder_channels canali scelti: 'telegram' | 'android' | 'smart_block'.

ALTER TABLE ps_objectives
  ADD COLUMN IF NOT EXISTS weigh_every_days  integer,
  ADD COLUMN IF NOT EXISTS reminder_enabled  boolean NOT NULL DEFAULT false,
  ADD COLUMN IF NOT EXISTS reminder_time     time,
  ADD COLUMN IF NOT EXISTS reminder_channels text[] NOT NULL DEFAULT '{}';

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conname = 'ps_objectives_weigh_every_days_check'
      AND conrelid = 'public.ps_objectives'::regclass
  ) THEN
    ALTER TABLE ps_objectives
      ADD CONSTRAINT ps_objectives_weigh_every_days_check
      CHECK (weigh_every_days IS NULL OR weigh_every_days >= 1);
  END IF;
END $$;
