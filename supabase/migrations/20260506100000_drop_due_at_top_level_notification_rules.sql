-- Rimuove la colonna due_at top-level da cm_notification_rules.
-- Il campo due_at è già presente dentro il JSONB reminder_presets,
-- che è l'unica sorgente letta da fill-notification-queue e rebuild-notification-rules.
-- La colonna top-level era ridondante e causava errori NOT NULL al salvataggio.

ALTER TABLE cm_notification_rules
    DROP COLUMN IF EXISTS due_at;
