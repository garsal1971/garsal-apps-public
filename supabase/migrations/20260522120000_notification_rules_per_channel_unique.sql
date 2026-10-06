-- Permette una riga per canale per task in cm_notification_rules.
-- Rimuove il vecchio vincolo su (user_id, app, entity_id) e lo sostituisce
-- con uno su (user_id, app, entity_id, channel), idempotente.

DO $$
DECLARE
  v_conname text;
BEGIN
  -- Trova ed elimina eventuali vincoli UNIQUE su esattamente (user_id, app, entity_id)
  SELECT con.conname INTO v_conname
  FROM pg_constraint con
  JOIN pg_class rel ON rel.oid = con.conrelid
  JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
  WHERE nsp.nspname = 'public'
    AND rel.relname = 'cm_notification_rules'
    AND con.contype = 'u'
    AND (
      SELECT array_agg(att.attname::text ORDER BY att.attname::text)
      FROM pg_attribute att
      WHERE att.attrelid = rel.oid
        AND att.attnum = ANY(SELECT unnest(con.conkey))
    ) = ARRAY['app', 'entity_id', 'user_id']::text[]
  LIMIT 1;

  IF v_conname IS NOT NULL THEN
    EXECUTE 'ALTER TABLE cm_notification_rules DROP CONSTRAINT ' || quote_ident(v_conname);
    RAISE NOTICE 'Rimosso constraint: %', v_conname;
  END IF;

  -- Aggiunge il nuovo vincolo (user_id, app, entity_id, channel) se non esiste già
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conname = 'cm_notification_rules_user_app_entity_channel_key'
      AND conrelid = 'public.cm_notification_rules'::regclass
  ) THEN
    ALTER TABLE cm_notification_rules
      ADD CONSTRAINT cm_notification_rules_user_app_entity_channel_key
      UNIQUE (user_id, app, entity_id, channel);
    RAISE NOTICE 'Aggiunto constraint: cm_notification_rules_user_app_entity_channel_key';
  END IF;
END $$;
