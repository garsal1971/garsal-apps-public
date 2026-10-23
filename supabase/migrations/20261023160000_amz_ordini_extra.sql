-- Scorte Amazon: gli ordini FUORI abbonamento («extra»).
-- Ogni tanto un prodotto in Iscriviti e risparmia si compra a parte: le confezioni
-- arrivate vanno sommate alla scorta, o la stima la dà più bassa del vero.
--
-- Un evento { tipo: 'extra', giorno: <data di CONSEGNA>, articoli: [ { asin, titolo,
-- quantita: <confezioni arrivate> } ] }:
--   - aggancia SOLO un abbonamento che esiste già (stesso riconoscimento degli altri
--     tipi): un articolo sconosciuto si scarta, non nasce un abbonamento da verificare;
--   - fissa un nuovo conteggio: scorta_contata = scorta stimata quel giorno + quantita,
--     scorta_contata_il = giorno — da lì amz__situazione somma le consegne e toglie il
--     consumo come per un conteggio fatto a mano;
--   - ⚠️ NON tocca quantita, frequenza né prossima_consegna dell'abbonamento: non è la
--     consegna in programma;
--   - ⚠️ NON entra nello storico prezzi: la riga in amz_consegne è 'manuale' con prezzo
--     NULL (un prezzo pieno farebbe scattare prezzo_su/prezzo_giu per sbaglio). Il tipo
--     resta 'manuale' così il CHECK della tabella non cambia; la nota dice che è un extra.
--   - un extra datato prima dell'ultimo conteggio si registra ma non cambia la scorta:
--     quel conteggio lo comprende già.
CREATE OR REPLACE FUNCTION amz_task_registra(p_token text, p_eventi jsonb)
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_user   uuid := amz__utente(p_token);
  e        jsonb;
  a        jsonb;
  v_tipo   text;
  v_mail   text;
  v_giorno date;
  v_id     uuid;
  v_ab     amz_abbonamenti;
  v_titolo text;
  v_asin   text;
  v_cons   date;
  v_int    interval;
  v_qta    int;
  v_scorta numeric;
  v_creati int := 0; v_agg int := 0; v_righe int := 0; v_gia int := 0; v_extra int := 0; v_scart int := 0;
BEGIN
  IF v_user IS NULL THEN RETURN jsonb_build_object('ok', false, 'error', 'chiave sconosciuta o revocata'); END IF;
  IF jsonb_typeof(p_eventi) <> 'array' THEN RETURN jsonb_build_object('ok', false, 'error', 'p_eventi deve essere un array'); END IF;

  FOR e IN SELECT * FROM jsonb_array_elements(p_eventi) LOOP
    v_tipo   := COALESCE(e->>'tipo', 'promemoria');
    IF v_tipo NOT IN ('iniziale', 'nuovo', 'promemoria', 'spedito', 'consegnato', 'modificato', 'saltato', 'annullato', 'manuale', 'extra') THEN
      v_tipo := 'promemoria';
    END IF;
    v_mail   := NULLIF(btrim(COALESCE(e->>'mail_id', '')), '');
    v_giorno := COALESCE((e->>'giorno')::date, current_date);

    FOR a IN SELECT * FROM jsonb_array_elements(COALESCE(e->'articoli', '[]'::jsonb)) LOOP
      v_asin   := NULLIF(upper(btrim(COALESCE(a->>'asin', ''))), '');
      v_titolo := NULLIF(btrim(COALESCE(a->>'titolo', '')), '');
      v_cons   := NULLIF(a->>'data_consegna', '')::date;
      v_id := NULL;

      -- riconoscimento: ASIN, poi titolo identico, poi un inizio di titolo che
      -- aggancia UN solo abbonamento (le mail troncano i nomi lunghi)
      IF v_asin IS NOT NULL THEN
        SELECT id INTO v_id FROM amz_abbonamenti WHERE user_id = v_user AND asin = v_asin;
      END IF;
      IF v_id IS NULL AND v_titolo IS NOT NULL THEN
        SELECT id INTO v_id FROM amz_abbonamenti
         WHERE user_id = v_user AND lower(btrim(titolo)) = lower(v_titolo) LIMIT 1;
      END IF;
      IF v_id IS NULL AND v_titolo IS NOT NULL AND length(v_titolo) >= 20 THEN
        SELECT CASE WHEN count(*) = 1 THEN min(id::text)::uuid END INTO v_id FROM amz_abbonamenti
         WHERE user_id = v_user
           AND (lower(titolo) LIKE lower(left(v_titolo, 30)) || '%'
                OR lower(v_titolo) LIKE lower(left(titolo, 30)) || '%');
      END IF;

      IF v_tipo = 'extra' THEN
        -- solo prodotti già in abbonamento; la mail si legge una volta sola
        IF v_id IS NULL THEN v_scart := v_scart + 1; CONTINUE; END IF;
        IF v_mail IS NOT NULL AND EXISTS (SELECT 1 FROM amz_consegne WHERE abbonamento_id = v_id AND mail_id = v_mail) THEN
          v_gia := v_gia + 1;
          CONTINUE;
        END IF;
        v_qta := GREATEST(COALESCE((a->>'quantita')::int, 1), 1);
        SELECT * INTO v_ab FROM amz_abbonamenti WHERE id = v_id;
        IF v_ab.scorta_contata_il IS NULL OR v_ab.scorta_contata_il <= v_giorno THEN
          SELECT (s->>'scorta')::numeric INTO v_scorta
            FROM jsonb_array_elements(amz__situazione(v_user, v_giorno)) s
           WHERE (s->>'id')::uuid = v_id;
          UPDATE amz_abbonamenti SET
            scorta_contata    = round(COALESCE(v_scorta, 0) + v_qta, 2),
            scorta_contata_il = v_giorno,
            asin              = COALESCE(asin, v_asin),
            updated_at        = now()
          WHERE id = v_id;
        END IF;
        INSERT INTO amz_consegne (user_id, abbonamento_id, giorno, tipo, prezzo, quantita, mail_id, nota)
        VALUES (v_user, v_id, v_giorno, 'manuale', NULL, v_qta, v_mail,
                COALESCE(NULLIF(a->>'nota', ''), 'extra: ordine fuori abbonamento'))
        ON CONFLICT DO NOTHING;
        v_extra := v_extra + 1;
        v_righe := v_righe + 1;
        CONTINUE;
      END IF;

      IF v_id IS NULL THEN
        IF v_titolo IS NULL THEN CONTINUE; END IF;
        INSERT INTO amz_abbonamenti (user_id, titolo, asin, pezzi, misura, unita, quantita,
                                     freq_valore, freq_unita, prossima_consegna, attivo, da_verificare)
        VALUES (v_user, v_titolo, v_asin,
                COALESCE((a->>'pezzi')::numeric, 1), (a->>'misura')::numeric,
                CASE WHEN a->>'unita' IN ('g', 'ml', 'pz') THEN a->>'unita' ELSE 'pz' END,
                COALESCE((a->>'quantita')::int, 1),
                (a->>'freq_valore')::int,
                CASE WHEN a->>'freq_unita' = 'settimane' THEN 'settimane' ELSE 'mesi' END,
                v_cons, v_tipo <> 'annullato', v_tipo <> 'iniziale')
        RETURNING id INTO v_id;
        v_creati := v_creati + 1;
      ELSE
        IF v_mail IS NOT NULL AND EXISTS (SELECT 1 FROM amz_consegne WHERE abbonamento_id = v_id AND mail_id = v_mail) THEN
          v_gia := v_gia + 1;
          CONTINUE;
        END IF;
        SELECT * INTO v_ab FROM amz_abbonamenti WHERE id = v_id;
        v_int := amz__intervallo(COALESCE((a->>'freq_valore')::int, v_ab.freq_valore),
                                 COALESCE(a->>'freq_unita', v_ab.freq_unita));
        -- spedito/consegnato senza la data della prossima: la consegna in
        -- programma è avvenuta, la prossima scivola di un periodo
        IF v_cons IS NULL AND v_tipo IN ('spedito', 'consegnato') AND v_int IS NOT NULL
           AND v_ab.prossima_consegna IS NOT NULL AND v_ab.prossima_consegna <= v_giorno + 3 THEN
          v_cons := (v_ab.prossima_consegna + v_int)::date;
        END IF;
        UPDATE amz_abbonamenti SET
          asin              = COALESCE(asin, v_asin),
          quantita          = COALESCE((a->>'quantita')::int, quantita),
          freq_valore       = COALESCE((a->>'freq_valore')::int, freq_valore),
          freq_unita        = CASE WHEN a->>'freq_unita' IN ('settimane', 'mesi') THEN a->>'freq_unita' ELSE freq_unita END,
          prossima_consegna = COALESCE(v_cons, prossima_consegna),
          attivo            = CASE WHEN v_tipo = 'annullato' THEN false
                                   WHEN v_tipo = 'nuovo' THEN true ELSE attivo END,
          updated_at        = now()
        WHERE id = v_id;
        v_agg := v_agg + 1;
      END IF;

      INSERT INTO amz_consegne (user_id, abbonamento_id, giorno, tipo, prezzo, prezzo_pieno, sconto_pct, quantita, mail_id, nota)
      VALUES (v_user, v_id, v_giorno, v_tipo,
              (a->>'prezzo')::numeric, (a->>'prezzo_pieno')::numeric, (a->>'sconto_pct')::numeric,
              (a->>'quantita')::int, v_mail, NULLIF(a->>'nota', ''))
      ON CONFLICT DO NOTHING;
      v_righe := v_righe + 1;
    END LOOP;
  END LOOP;

  RETURN jsonb_build_object('ok', true, 'creati', v_creati, 'aggiornati', v_agg,
                            'righe', v_righe, 'gia_lette', v_gia,
                            'extra', v_extra, 'extra_scartati', v_scart);
END;
$$;
REVOKE ALL ON FUNCTION amz_task_registra(text, jsonb) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION amz_task_registra(text, jsonb) TO anon, authenticated;
