-- Inoltro Messaggi (APK nativa): gli SMS e i messaggi WhatsApp ricevuti, perché
-- Claude li legga dal connettore Supabase quando glielo si chiede.
--
-- ⚠️ SCRIVE SOLO LA EDGE FUNCTION `msg-ingest` (service role): nessuna policy
-- di INSERT/UPDATE/DELETE. Il telefono legge le sue righe col JWT (policy SELECT).
-- ⚠️ `impronta` (SHA-256 di fonte, mittente, chat, testo e minuto, calcolata sul
-- telefono) è UNICA: WhatsApp ripresenta la stessa notifica più volte, e
-- l'insert la scarta con ON CONFLICT DO NOTHING.
-- ⚠️ Nessun dato qui: le righe nascono solo dal telefono.

CREATE TABLE IF NOT EXISTS public.msg_messaggi (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  fonte       text NOT NULL CHECK (fonte IN ('sms', 'whatsapp')),
  mittente    text,
  chat        text,           -- nome del gruppo WhatsApp; NULL per SMS e chat singole
  testo       text NOT NULL,
  ricevuto_il timestamptz NOT NULL,
  creato_il   timestamptz NOT NULL DEFAULT now(),
  impronta    text NOT NULL UNIQUE
);

CREATE INDEX IF NOT EXISTS msg_messaggi_ricevuto_idx
  ON public.msg_messaggi (user_id, ricevuto_il DESC);

ALTER TABLE public.msg_messaggi ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS msg_messaggi_select_owner ON public.msg_messaggi;
CREATE POLICY msg_messaggi_select_owner ON public.msg_messaggi
  FOR SELECT USING (user_id = auth.uid());

-- Pulizia: via i messaggi ricevuti più di 90 giorni fa, ogni notte alle 03:17 UTC.
DO $$
BEGIN
  PERFORM cron.unschedule('msg-pulizia-90gg')
    WHERE EXISTS (SELECT 1 FROM cron.job WHERE jobname = 'msg-pulizia-90gg');
END $$;

SELECT cron.schedule(
  'msg-pulizia-90gg',
  '17 3 * * *',
  $$DELETE FROM public.msg_messaggi WHERE ricevuto_il < now() - interval '90 days'$$
);
