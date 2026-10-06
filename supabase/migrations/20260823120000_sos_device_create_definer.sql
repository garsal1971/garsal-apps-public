-- ============================================================
-- sos_device_create — da SECURITY INVOKER a SECURITY DEFINER
-- ============================================================
-- La funzione genera un codice di accoppiamento e prima di usarlo controlla che
-- non esista già. Sotto la RLS quel controllo vede però i soli codici
-- dell'utente corrente: un codice già assegnato a un altro utente sarebbe
-- invisibile, il ciclo uscirebbe convinto che sia libero e l'INSERT
-- fallirebbe sull'indice unico — un errore secco al posto di un altro giro di
-- dado. Il vincolo `sos_devices_token_key` protegge comunque il database, ma
-- l'errore arriverebbe all'utente.
--
-- SECURITY DEFINER non allarga niente: l'utente resta quello del JWT, perché
-- auth.uid() legge il claim e non il ruolo di esecuzione, e l'INSERT lo scrive
-- esplicitamente. La funzione resta eseguibile dai soli `authenticated`.
--
-- (La migration che ha creato la funzione è già stata applicata: la correzione
-- deve viaggiare su un file nuovo, o in produzione non girerebbe mai.)

CREATE OR REPLACE FUNCTION sos_device_create(p_name text DEFAULT 'Telefono')
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_token text;
  v_id    uuid;
BEGIN
  IF auth.uid() IS NULL THEN
    RETURN jsonb_build_object('ok', false, 'error', 'non autenticato');
  END IF;
  LOOP
    v_token := sos_gen_token();
    EXIT WHEN NOT EXISTS (SELECT 1 FROM sos_devices WHERE token = v_token);
  END LOOP;
  INSERT INTO sos_devices (user_id, token, name)
  VALUES (auth.uid(), v_token, COALESCE(NULLIF(btrim(p_name), ''), 'Telefono'))
  RETURNING id INTO v_id;
  RETURN jsonb_build_object('ok', true, 'id', v_id, 'token', v_token);
END;
$$;

REVOKE ALL ON FUNCTION sos_device_create(text) FROM anon, public;
GRANT EXECUTE ON FUNCTION sos_device_create(text) TO authenticated;
