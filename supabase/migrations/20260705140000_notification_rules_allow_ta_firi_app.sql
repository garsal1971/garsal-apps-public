-- Allarga il vincolo cm_notification_rules_app_check per ammettere 'ta_firi'
-- (regole smart_block delle sfide Ta Firi?). Modifica additiva: tasks, habits,
-- events, weight, quick restano invariati.
ALTER TABLE cm_notification_rules DROP CONSTRAINT IF EXISTS cm_notification_rules_app_check;
ALTER TABLE cm_notification_rules ADD CONSTRAINT cm_notification_rules_app_check
  CHECK (app = ANY (ARRAY['tasks'::text, 'habits'::text, 'events'::text, 'weight'::text, 'quick'::text, 'ta_firi'::text]));
