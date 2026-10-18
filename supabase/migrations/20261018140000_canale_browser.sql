-- ============================================================================
-- Il canale 'browser': le notifiche Web Push sul PC
-- ============================================================================
-- Un quarto canale accanto a telegram, android e smart_block: una regola per
-- canale come gli altri, consegnata da send-notifications ai browser abbonati.
--
-- I browser stanno in cm_push_devices insieme ai telefoni, con platform = 'web':
--   token        = l'endpoint dell'abbonamento (unico, come il token FCM)
--   web_push     = l'abbonamento intero {endpoint, keys:{p256dh, auth}}, che
--                  serve a cifrare il messaggio — il solo endpoint non basta
--   device_name  = il nome dato dall'utente («PC casa», «PC lavoro»)
--   enabled      = l'interruttore
-- ⚠️ Chi legge cm_push_devices per FCM deve escludere platform = 'web'.
-- ============================================================================

ALTER TABLE cm_push_devices ADD COLUMN IF NOT EXISTS web_push jsonb;

COMMENT ON COLUMN cm_push_devices.web_push IS
  'Solo per platform = ''web'': l''abbonamento Web Push intero (endpoint e chiavi p256dh/auth). NULL sui telefoni.';

-- Il CHECK sul canale si allarga come in 20260903100000: si cerca per
-- definizione (il nome non è garantito), e si tengono i valori già in tabella.
DO $$
DECLARE
  v_tabella text;
  v_vincolo text;
  v_valori  text;
BEGIN
  FOREACH v_tabella IN ARRAY ARRAY['cm_notification_rules', 'cm_notification_queue'] LOOP
    SELECT c.conname INTO v_vincolo
      FROM pg_constraint c
      JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = v_tabella
       AND c.contype = 'c'
       AND pg_get_constraintdef(c.oid) ILIKE '%channel%'
       AND pg_get_constraintdef(c.oid) ILIKE '%telegram%'
     LIMIT 1;

    IF v_vincolo IS NULL THEN
      RAISE NOTICE '% : nessun CHECK sul canale, niente da allargare', v_tabella;
      CONTINUE;
    END IF;

    EXECUTE format($q$SELECT string_agg(quote_literal(c), ', ' ORDER BY c) FROM (
        SELECT unnest(ARRAY['telegram', 'android', 'smart_block', 'browser']) AS c
        UNION SELECT DISTINCT channel FROM %I WHERE channel IS NOT NULL) s$q$, v_tabella)
      INTO v_valori;

    EXECUTE format('ALTER TABLE %I DROP CONSTRAINT %I', v_tabella, v_vincolo);
    EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I CHECK (channel = ANY (ARRAY[%s]::text[]))',
                   v_tabella, v_vincolo, v_valori);
    RAISE NOTICE '% : vincolo % allargato a [%]', v_tabella, v_vincolo, v_valori;
  END LOOP;
END $$;
