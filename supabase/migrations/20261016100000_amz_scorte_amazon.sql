-- ============================================================================
-- Scorte Amazon — gli abbonamenti «Iscriviti e risparmia», i loro prezzi e la
-- scorta che resta in casa.
--
--   • amz_abbonamenti  un abbonamento per riga: formato, frequenza, prossima consegna
--   • amz_consegne     lo storico: ogni consegna/promemoria letto, col prezzo di allora
--   • amz_chiavi       le chiavi con cui l'attività programmata che legge le mail
--                      scrive qui senza un login (come sos_devices)
--
-- ⚠️ Nessun dato in questa migration: la repo è pubblica. Gli abbonamenti si
-- caricano dalla pagina o dall'attività programmata.
--
-- ⚠️ Prezzi per pezzo e al kg/l, scorta stimata e avvisi NON si archiviano: li
-- calcola amz__situazione(), l'unica regola, che leggono la pagina, il recap di
-- AppSphere e l'attività programmata. Tre copie della stessa stima direbbero
-- tre scorte diverse il giorno che una cambia.
-- ============================================================================

CREATE TABLE IF NOT EXISTS amz_abbonamenti (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id            uuid NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
  titolo             text NOT NULL CHECK (length(btrim(titolo)) > 0),
  asin               text,
  -- Il formato di UNA confezione (un'«unità» di Amazon): quanti pezzi e quanto
  -- pesa/contiene ciascuno. 5 × 650 ml → pezzi 5, misura 650, unita 'ml'.
  -- misura NULL = non nota: niente prezzo al kg/l, non zero.
  pezzi              numeric NOT NULL DEFAULT 1 CHECK (pezzi > 0),
  misura             numeric CHECK (misura > 0),
  unita              text NOT NULL DEFAULT 'pz' CHECK (unita IN ('g', 'ml', 'pz')),
  quantita           int  NOT NULL DEFAULT 1 CHECK (quantita > 0),   -- confezioni per consegna
  freq_valore        int  CHECK (freq_valore > 0),                   -- NULL = non nota
  freq_unita         text NOT NULL DEFAULT 'mesi' CHECK (freq_unita IN ('settimane', 'mesi')),
  prossima_consegna  date,
  attivo             boolean NOT NULL DEFAULT true,
  -- Nato o cambiato da una mail che non è stato possibile leggere per intero:
  -- la pagina lo segnala finché qualcuno non lo guarda.
  da_verificare      boolean NOT NULL DEFAULT false,
  -- Facoltativo: quante confezioni c'erano davvero in casa quel giorno. Senza,
  -- la scorta si stima partendo piena dall'ultima consegna.
  scorta_contata     numeric CHECK (scorta_contata >= 0),
  scorta_contata_il  date,
  note               text,
  created_at         timestamptz NOT NULL DEFAULT now(),
  updated_at         timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS amz_abbonamenti_asin_uq ON amz_abbonamenti (user_id, asin) WHERE asin IS NOT NULL;
ALTER TABLE amz_abbonamenti ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS amz_abbonamenti_owner ON amz_abbonamenti;
CREATE POLICY amz_abbonamenti_owner ON amz_abbonamenti
  FOR ALL USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());

CREATE TABLE IF NOT EXISTS amz_consegne (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id         uuid NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
  abbonamento_id  uuid NOT NULL REFERENCES amz_abbonamenti(id) ON DELETE CASCADE,
  giorno          date NOT NULL,
  tipo            text NOT NULL CHECK (tipo IN ('iniziale', 'nuovo', 'promemoria', 'spedito',
                                                'consegnato', 'modificato', 'saltato', 'annullato', 'manuale')),
  prezzo          numeric CHECK (prezzo >= 0),       -- per confezione, già scontato
  prezzo_pieno    numeric CHECK (prezzo_pieno >= 0),
  sconto_pct      numeric,
  quantita        int,
  mail_id         text,                              -- id Gmail: rileggere la stessa mail non raddoppia
  nota            text,
  created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS amz_consegne_mail_uq ON amz_consegne (abbonamento_id, mail_id) WHERE mail_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS amz_consegne_abb_idx ON amz_consegne (abbonamento_id, giorno DESC);
ALTER TABLE amz_consegne ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS amz_consegne_owner ON amz_consegne;
CREATE POLICY amz_consegne_owner ON amz_consegne
  FOR ALL USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());

CREATE TABLE IF NOT EXISTS amz_chiavi (
  token        text PRIMARY KEY,
  user_id      uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  nome         text NOT NULL DEFAULT 'Attività programmata',
  created_at   timestamptz NOT NULL DEFAULT now(),
  ultimo_uso   timestamptz
);
ALTER TABLE amz_chiavi ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS amz_chiavi_lettura ON amz_chiavi;
CREATE POLICY amz_chiavi_lettura ON amz_chiavi FOR SELECT USING (user_id = auth.uid());
DROP POLICY IF EXISTS amz_chiavi_revoca ON amz_chiavi;
CREATE POLICY amz_chiavi_revoca ON amz_chiavi FOR DELETE USING (user_id = auth.uid());

-- ── la regola: prezzi, scorta, avvisi ────────────────────────────────────────
CREATE OR REPLACE FUNCTION amz__intervallo(p_valore int, p_unita text)
RETURNS interval LANGUAGE sql IMMUTABLE AS $$
  SELECT CASE WHEN p_valore IS NULL THEN NULL
              WHEN p_unita = 'settimane' THEN make_interval(weeks => p_valore)
              ELSE make_interval(months => p_valore) END;
$$;

CREATE OR REPLACE FUNCTION amz__situazione(p_user uuid, p_oggi date)
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  r          record;
  v_out      jsonb := '[]'::jsonb;
  v_int      interval;
  v_ult      date;
  v_periodo  int;
  v_cons     numeric;
  v_scorta   numeric;
  v_alla     numeric;
  v_esaur    date;
  v_n        int;
  v_d        date;
  v_p1       numeric;  v_p1_g date;
  v_p0       numeric;
  v_kg       numeric;
  v_avvisi   text[];
BEGIN
  FOR r IN SELECT * FROM amz_abbonamenti WHERE user_id = p_user
           ORDER BY attivo DESC, prossima_consegna NULLS LAST, titolo LOOP
    v_avvisi := ARRAY[]::text[];
    v_scorta := NULL; v_alla := NULL; v_esaur := NULL; v_cons := NULL; v_ult := NULL; v_periodo := NULL;

    -- gli ultimi due prezzi noti
    SELECT prezzo, giorno INTO v_p1, v_p1_g FROM amz_consegne
     WHERE abbonamento_id = r.id AND prezzo IS NOT NULL
     ORDER BY giorno DESC, created_at DESC LIMIT 1;
    SELECT prezzo INTO v_p0 FROM amz_consegne
     WHERE abbonamento_id = r.id AND prezzo IS NOT NULL
     ORDER BY giorno DESC, created_at DESC OFFSET 1 LIMIT 1;
    IF NOT FOUND THEN v_p0 := NULL; END IF;

    v_kg := CASE WHEN v_p1 IS NOT NULL AND r.misura IS NOT NULL AND r.unita IN ('g', 'ml')
                 THEN round(v_p1 / (r.pezzi * r.misura / 1000.0), 2) END;

    v_int := amz__intervallo(r.freq_valore, r.freq_unita);
    IF r.attivo AND v_int IS NOT NULL AND r.prossima_consegna IS NOT NULL THEN
      v_ult     := (r.prossima_consegna - v_int)::date;
      v_periodo := r.prossima_consegna - v_ult;
      v_cons    := r.quantita::numeric / v_periodo;          -- confezioni al giorno
      IF r.scorta_contata IS NOT NULL AND r.scorta_contata_il IS NOT NULL THEN
        -- dal conteggio: più le consegne arrivate dopo, meno il consumo
        v_n := 0; v_d := r.prossima_consegna;
        WHILE v_d > r.scorta_contata_il LOOP
          IF v_d <= p_oggi THEN v_n := v_n + 1; END IF;
          v_d := (v_d - v_int)::date;
        END LOOP;
        v_scorta := r.scorta_contata + v_n * r.quantita - v_cons * (p_oggi - r.scorta_contata_il);
      ELSE
        -- senza conteggio: piena all'ultima consegna, vuota alla prossima
        v_scorta := r.quantita - v_cons * (p_oggi - v_ult);
      END IF;
      v_esaur := p_oggi + floor(v_scorta / v_cons)::int;
      IF r.prossima_consegna > p_oggi THEN
        v_alla := v_scorta - v_cons * (r.prossima_consegna - p_oggi);
      END IF;

      IF r.prossima_consegna < p_oggi THEN v_avvisi := v_avvisi || 'consegna_passata'::text;
      ELSIF r.prossima_consegna - p_oggi <= 7 THEN v_avvisi := v_avvisi || 'consegna_vicina'::text; END IF;
      IF r.scorta_contata IS NOT NULL AND v_esaur < r.prossima_consegna THEN
        v_avvisi := v_avvisi || 'finisce_prima'::text;
      END IF;
      IF r.scorta_contata IS NOT NULL AND v_alla IS NOT NULL AND v_alla >= r.quantita THEN
        v_avvisi := v_avvisi || 'troppa_scorta'::text;
      END IF;
    ELSIF r.attivo THEN
      v_avvisi := v_avvisi || 'dati_mancanti'::text;
    END IF;

    IF r.attivo AND v_p1 IS NULL THEN v_avvisi := v_avvisi || 'senza_prezzo'::text; END IF;
    IF v_p1 IS NOT NULL AND v_p0 IS NOT NULL AND v_p1 > v_p0 THEN v_avvisi := v_avvisi || 'prezzo_su'::text; END IF;
    IF v_p1 IS NOT NULL AND v_p0 IS NOT NULL AND v_p1 < v_p0 THEN v_avvisi := v_avvisi || 'prezzo_giu'::text; END IF;
    IF r.da_verificare THEN v_avvisi := v_avvisi || 'da_verificare'::text; END IF;

    v_out := v_out || jsonb_build_object(
      'id', r.id, 'titolo', r.titolo, 'asin', r.asin,
      'pezzi', r.pezzi, 'misura', r.misura, 'unita', r.unita,
      'quantita', r.quantita, 'freq_valore', r.freq_valore, 'freq_unita', r.freq_unita,
      'prossima_consegna', r.prossima_consegna, 'attivo', r.attivo, 'da_verificare', r.da_verificare,
      'scorta_contata', r.scorta_contata, 'scorta_contata_il', r.scorta_contata_il, 'note', r.note,
      'prezzo', v_p1, 'prezzo_del', v_p1_g, 'prezzo_prima', v_p0,
      'prezzo_pezzo', CASE WHEN v_p1 IS NOT NULL THEN round(v_p1 / r.pezzi, 3) END,
      'prezzo_kg', v_kg,
      'spesa_mese', CASE WHEN v_p1 IS NOT NULL AND v_periodo IS NOT NULL
                         THEN round(v_p1 * r.quantita * 30.4375 / v_periodo, 2) END,
      'ultima_consegna', v_ult,
      'scorta', CASE WHEN v_scorta IS NOT NULL THEN round(greatest(v_scorta, 0), 2) END,
      'scorta_alla_consegna', CASE WHEN v_alla IS NOT NULL THEN round(v_alla, 2) END,
      'esaurimento', v_esaur,
      'avvisi', to_jsonb(v_avvisi));
  END LOOP;
  RETURN v_out;
END;
$$;
REVOKE ALL ON FUNCTION amz__situazione(uuid, date) FROM PUBLIC;

-- La porta della pagina e del recap: l'utente è quello del JWT. «Oggi» lo passa
-- il client, come p_today in task_complete: il database sta in UTC.
CREATE OR REPLACE FUNCTION amz_situazione(p_oggi date)
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
  IF auth.uid() IS NULL THEN RAISE EXCEPTION 'non autenticato'; END IF;
  RETURN amz__situazione(auth.uid(), p_oggi);
END;
$$;
REVOKE ALL ON FUNCTION amz_situazione(date) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION amz_situazione(date) TO authenticated;

-- ── le chiavi dell'attività programmata ─────────────────────────────────────
CREATE OR REPLACE FUNCTION amz_chiave_crea(p_nome text DEFAULT 'Attività programmata')
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public
AS $$
DECLARE v_token text;
BEGIN
  IF auth.uid() IS NULL THEN RETURN jsonb_build_object('ok', false, 'error', 'non autenticato'); END IF;
  v_token := 'amz_' || replace(gen_random_uuid()::text || gen_random_uuid()::text, '-', '');
  INSERT INTO amz_chiavi (token, user_id, nome)
  VALUES (v_token, auth.uid(), COALESCE(NULLIF(btrim(p_nome), ''), 'Attività programmata'));
  RETURN jsonb_build_object('ok', true, 'token', v_token);
END;
$$;
REVOKE ALL ON FUNCTION amz_chiave_crea(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION amz_chiave_crea(text) TO authenticated;

CREATE OR REPLACE FUNCTION amz__utente(p_token text)
RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public
AS $$
DECLARE v_user uuid;
BEGIN
  SELECT user_id INTO v_user FROM amz_chiavi WHERE token = btrim(COALESCE(p_token, ''));
  IF v_user IS NOT NULL THEN
    UPDATE amz_chiavi SET ultimo_uso = now() WHERE token = btrim(p_token);
  END IF;
  RETURN v_user;
END;
$$;
REVOKE ALL ON FUNCTION amz__utente(text) FROM PUBLIC;

-- Cosa l'attività programmata deve sapere prima di leggere le mail: gli
-- abbonamenti (per riconoscerli) e le mail già lette (per non rileggerle).
CREATE OR REPLACE FUNCTION amz_task_stato(p_token text, p_oggi date)
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public
AS $$
DECLARE v_user uuid := amz__utente(p_token);
BEGIN
  IF v_user IS NULL THEN RETURN jsonb_build_object('ok', false, 'error', 'chiave sconosciuta o revocata'); END IF;
  RETURN jsonb_build_object(
    'ok', true,
    'abbonamenti', amz__situazione(v_user, p_oggi),
    'mail_lette', COALESCE((SELECT jsonb_agg(DISTINCT mail_id) FROM amz_consegne
                             WHERE user_id = v_user AND mail_id IS NOT NULL
                               AND created_at > now() - interval '1 year'), '[]'::jsonb));
END;
$$;
REVOKE ALL ON FUNCTION amz_task_stato(text, date) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION amz_task_stato(text, date) TO anon, authenticated;

-- Applica quel che le mail dicono. p_eventi è un array di
--   { mail_id, giorno, tipo, articoli: [ { asin, titolo, prezzo, prezzo_pieno,
--     sconto_pct, quantita, freq_valore, freq_unita, data_consegna,
--     pezzi, misura, unita } ] }
-- ⚠️ Idempotente: una mail già registrata per un abbonamento non si riapplica.
-- ⚠️ Un articolo che non si riconosce nasce come abbonamento nuovo con
-- da_verificare = true: meglio una riga da controllare che una mail persa.
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
  v_creati int := 0; v_agg int := 0; v_righe int := 0; v_gia int := 0;
BEGIN
  IF v_user IS NULL THEN RETURN jsonb_build_object('ok', false, 'error', 'chiave sconosciuta o revocata'); END IF;
  IF jsonb_typeof(p_eventi) <> 'array' THEN RETURN jsonb_build_object('ok', false, 'error', 'p_eventi deve essere un array'); END IF;

  FOR e IN SELECT * FROM jsonb_array_elements(p_eventi) LOOP
    v_tipo   := COALESCE(e->>'tipo', 'promemoria');
    IF v_tipo NOT IN ('iniziale', 'nuovo', 'promemoria', 'spedito', 'consegnato', 'modificato', 'saltato', 'annullato', 'manuale') THEN
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
                            'righe', v_righe, 'gia_lette', v_gia);
END;
$$;
REVOKE ALL ON FUNCTION amz_task_registra(text, jsonb) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION amz_task_registra(text, jsonb) TO anon, authenticated;

-- ── la bolla in AppSphere ───────────────────────────────────────────────────
-- Il numero sono gli abbonamenti attivi: un conteggio, quindi conta_punti = false.
DO $$
DECLARE v_colore text;
BEGIN
  IF EXISTS (SELECT 1 FROM cm_apps WHERE html_file = 'scorte-amazon.html') THEN RETURN; END IF;
  -- ⚠️ Colore DISTINTO: il codice della modalità nascosta è una sequenza di colori.
  SELECT c INTO v_colore
    FROM unnest(ARRAY['#FF9900', '#E47911', '#C45500', '#F0A030', '#B45309']) WITH ORDINALITY AS t(c, n)
   WHERE NOT EXISTS (SELECT 1 FROM cm_apps a WHERE upper(trim(a.color)) = upper(c))
   ORDER BY n LIMIT 1;
  INSERT INTO cm_apps (title, description, score_query, color, active, html_file, riservato, conta_punti)
  VALUES ('Scorte Amazon',
          'Gli abbonamenti Iscriviti e risparmia: prezzi, prezzo al kg e scorte',
          $q$SELECT COUNT(*)::int FROM amz_abbonamenti WHERE user_id = auth.uid() AND attivo$q$,
          v_colore, true, 'scorte-amazon.html', false, false);
END;
$$;
