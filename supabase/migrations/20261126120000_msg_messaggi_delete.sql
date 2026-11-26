-- Inoltro Messaggi: il proprietario può cancellare i propri messaggi.
--
-- Serve a «✗ Filtra questo mittente» della schermata 📨 Inoltro dell'APK
-- nativa: il mittente passa fra i filtrati sul telefono e i suoi messaggi già
-- in archivio si cancellano. Le scritture restano della sola `msg-ingest`.

DROP POLICY IF EXISTS msg_messaggi_delete_owner ON public.msg_messaggi;
CREATE POLICY msg_messaggi_delete_owner ON public.msg_messaggi
  FOR DELETE USING (user_id = auth.uid());
