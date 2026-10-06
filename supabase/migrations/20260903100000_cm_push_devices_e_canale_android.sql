-- ============================================================================
-- Notifiche Android native: i telefoni a cui mandare le push, e il canale nuovo
--
-- Il giro delle notifiche esiste già ed è per canale:
--   cm_notification_rules (una riga per user+app+entità+CANALE)
--     → fill-notification-queue riempie cm_notification_queue con un fire_at
--       → send-notifications manda le righe 'telegram' al bot
--       → l'APK Smart Blocker si prende in polling le righe 'smart_block'
--
-- Qui si aggiunge il terzo canale, 'android': le stesse notifiche arrivano
-- anche sull'APK nativo (com.garsal.appsphere) come push FCM, ACCANTO a quelle
-- di Telegram e non al loro posto.
--
-- ⚠️ Le due tabelle cm_notification_* non stanno in nessuna migration (sono
-- nate a mano in produzione), quindi qui non si creano: si allarga soltanto il
-- vincolo che elenca i canali ammessi.
-- ============================================================================

-- ── I telefoni ──────────────────────────────────────────────────────────────
--
-- Una riga per installazione dell'app, non per persona: lo stesso account può
-- avere il telefono e il tablet, e ciascuno ha il suo token.
--
-- ⚠️ Il token è UNIQUE ed è la chiave vera. Non è un identificativo stabile del
-- telefono: FCM lo rigenera da sé (reinstallazione, ripristino da backup,
-- svuotamento dei dati), e lo stesso token può passare da un account all'altro
-- se sul telefono si cambia utente. Per questo l'app lo riscrive a ogni avvio
-- in upsert sul token, aggiornando anche user_id: senza, una push finirebbe
-- alla persona sbagliata.
CREATE TABLE IF NOT EXISTS cm_push_devices (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id      uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  token        text NOT NULL UNIQUE,
  platform     text NOT NULL DEFAULT 'android',
  app_version  text,
  device_name  text,
  -- Spento dal server quando FCM risponde che quel token non esiste più
  -- (UNREGISTERED): una riga morta che resta accesa fa fallire ogni invio
  -- successivo e non lo dice a nessuno.
  enabled      boolean NOT NULL DEFAULT true,
  created_at   timestamptz NOT NULL DEFAULT now(),
  last_seen_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS cm_push_devices_user_idx
  ON cm_push_devices (user_id) WHERE enabled;

COMMENT ON TABLE cm_push_devices IS
  'I telefoni a cui mandare le notifiche push FCM. Una riga per installazione: il token è la chiave, e l''app lo riscrive a ogni avvio perché FCM lo rigenera da sé.';

ALTER TABLE cm_push_devices ENABLE ROW LEVEL SECURITY;

-- Ognuno vede e scrive solo i propri telefoni. Il service role (send-notifications)
-- scavalca la RLS e li legge tutti, che è quello che serve per mandare.
DROP POLICY IF EXISTS cm_push_devices_owner ON cm_push_devices;
CREATE POLICY cm_push_devices_owner ON cm_push_devices
  FOR ALL
  USING (auth.uid() = user_id)
  WITH CHECK (auth.uid() = user_id);

-- ── Il canale 'android' ─────────────────────────────────────────────────────
--
-- ⚠️ Il vincolo si ALLARGA, non si riscrive da capo: i valori ammessi oggi non
-- sono scritti in nessuna migration (c'è almeno 'telegram', 'smart_block' e il
-- 'both' rimasto dalla transizione v19.19.2 di tasks.html), e un elenco scritto
-- a mano qui butterebbe fuori una riga che esiste davvero — cioè un deploy che
-- fallisce a metà. Si legge quindi cosa c'è in tabella e ci si aggiunge
-- 'android'.
--
-- ⚠️ Se un CHECK non c'è affatto non se ne inventa uno: sarebbe una stretta,
-- non un allargamento, e il giorno che qualcuno scrive un canale nuovo si
-- troverebbe rifiutato da una regola che nessuno ricorda di aver messo.
DO $$
DECLARE
  v_tabella  text;
  v_vincolo  text;
  v_valori   text;
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

    -- I canali che esistono davvero nelle righe, più quello nuovo.
    EXECUTE format(
      'SELECT string_agg(DISTINCT quote_literal(channel), '', '') FROM %I WHERE channel IS NOT NULL',
      v_tabella
    ) INTO v_valori;

    v_valori := coalesce(v_valori || ', ', '') || quote_literal('android');
    -- 'telegram' e 'smart_block' vanno nominati comunque: una tabella svuotata
    -- non li avrebbe fra le righe, e il vincolo nuovo li escluderebbe.
    IF position('''telegram''' in v_valori) = 0 THEN
      v_valori := v_valori || ', ' || quote_literal('telegram');
    END IF;
    IF position('''smart_block''' in v_valori) = 0 THEN
      v_valori := v_valori || ', ' || quote_literal('smart_block');
    END IF;

    EXECUTE format('ALTER TABLE %I DROP CONSTRAINT %I', v_tabella, v_vincolo);
    EXECUTE format(
      'ALTER TABLE %I ADD CONSTRAINT %I CHECK (channel = ANY (ARRAY[%s]::text[]))',
      v_tabella, v_vincolo, v_valori
    );

    RAISE NOTICE '% : vincolo % allargato a [%]', v_tabella, v_vincolo, v_valori;
  END LOOP;
END $$;
