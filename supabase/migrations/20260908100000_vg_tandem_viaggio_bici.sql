-- ============================================================
-- Tandem — le spese di un viaggio in bici, divise fra due persone
-- ============================================================
-- Due telefoni, la stessa APK (`com.garsal.tandem`), nessun login: chi crea il
-- viaggio ottiene un CODICE, l'altro lo digita, e da lì in poi i due parlano
-- con le sole RPC qui sotto. È la stessa scelta di `sos_*`, e per la stessa
-- ragione: l'app la si apre in mezzo alla strada, e inciampare in una sessione
-- scaduta o in una schermata di Google che chiede di riautenticarsi è il modo
-- peggiore di segnare uno scontrino.
--
-- ⚠️ NESSUNA di queste tabelle ha `user_id`, e non è una dimenticanza: il
-- viaggio non appartiene a un account Supabase ma a un codice. La RLS resta
-- accesa e SENZA POLICY — da PostgREST con la anon key non si legge e non si
-- scrive niente — e tutto passa dalle funzioni SECURITY DEFINER, che
-- riconoscono chi chiama dal token del suo telefono.
--
-- ⚠️ Le regole del ciclo di vita di una voce (chi può modificare, chi conferma,
-- come si cancella) vivono QUI e non in Kotlin. È la stessa regola di
-- `task_complete` e `sos_session_finish`: i due telefoni darebbero due verità
-- diverse sulla stessa spesa il giorno che una delle due copie cambia — e qui
-- la verità è un debito fra due persone.

-- ---------------------------------------------------------------------------
-- 1. vg_viaggi — un giro in bici. Più di uno: il prossimo non chiede una
--    migration, e i saldi si chiudono viaggio per viaggio.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS vg_viaggi (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  nome        text NOT NULL,
  data_inizio date,
  data_fine   date,
  valuta      text NOT NULL DEFAULT 'EUR',
  -- il codice di invito: si legge da uno schermo e si digita sull'altro
  codice      text NOT NULL UNIQUE,
  chiuso      boolean NOT NULL DEFAULT false,
  created_at  timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT vg_viaggi_date_ok CHECK (data_fine IS NULL OR data_inizio IS NULL OR data_fine >= data_inizio)
);
ALTER TABLE vg_viaggi ENABLE ROW LEVEL SECURITY;

-- ---------------------------------------------------------------------------
-- 2. vg_partecipanti — i due telefoni, uno per persona.
--    `token` È la credenziale: chi ce l'ha è quella persona. Sta in una
--    tabella senza policy, quindi non si legge da nessun client; le RPC lo
--    consultano da SECURITY DEFINER e non lo restituiscono mai.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS vg_partecipanti (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  viaggio_id   uuid NOT NULL REFERENCES vg_viaggi(id) ON DELETE CASCADE,
  nome         text NOT NULL,
  token        text NOT NULL UNIQUE,
  last_seen_at timestamptz,
  created_at   timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_vg_partecipanti_nome UNIQUE (viaggio_id, nome)
);
CREATE INDEX IF NOT EXISTS idx_vg_partecipanti_viaggio ON vg_partecipanti(viaggio_id);
ALTER TABLE vg_partecipanti ENABLE ROW LEVEL SECURITY;

-- ⚠️ Due e non più di due, e il vincolo sta sulla tabella e non nella sola RPC:
-- tutta l'app — il saldo, «l'altro», la conferma — è scritta su due persone, e
-- una terza riga qui dentro renderebbe ambiguo ogni «l'altro» senza dare
-- nessun errore.
CREATE OR REPLACE FUNCTION vg_partecipanti_max_due()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF (SELECT COUNT(*) FROM vg_partecipanti WHERE viaggio_id = NEW.viaggio_id) >= 2 THEN
    RAISE EXCEPTION 'Il viaggio ha già due partecipanti';
  END IF;
  RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS trg_vg_partecipanti_max_due ON vg_partecipanti;
CREATE TRIGGER trg_vg_partecipanti_max_due
  BEFORE INSERT ON vg_partecipanti
  FOR EACH ROW EXECUTE FUNCTION vg_partecipanti_max_due();

-- ---------------------------------------------------------------------------
-- 3. vg_voci — spese e restituzioni, una riga ciascuna.
--
--    SPESA:        `da_id` è chi ha messo i soldi (di norma il proprietario del
--                  telefono, ma si può registrare una spesa fatta dall'altro),
--                  `per_chi` dice per chi vale — 'entrambi' è il default;
--                  'uno' + `beneficiario_id` per una spesa di una persona sola.
--    RESTITUZIONE: `da_id` restituisce all'altro. Con due partecipanti «a chi»
--                  si ricava e non si scrive: una colonna che può avere un
--                  valore solo è un valore che prima o poi contraddice l'altro.
--
--    ⚠️ Lo stato è il cuore dell'app: una voce nasce `in_attesa`, e finché è lì
--    solo chi l'ha scritta può correggerla o toglierla. Confermata dall'altro
--    non si tocca più — per cancellarla si chiede, e l'altro approva.
--    Una voce cancellata NON sparisce dalla tabella: resta, barrata e fuori dal
--    saldo. Sparire sarebbe il modo peggiore di dirlo, un totale più basso
--    senza niente che spieghi perché.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS vg_voci (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  viaggio_id      uuid NOT NULL REFERENCES vg_viaggi(id) ON DELETE CASCADE,
  tipo            text NOT NULL CHECK (tipo IN ('spesa', 'restituzione')),
  importo         numeric(12,2) NOT NULL CHECK (importo > 0),
  data            date NOT NULL DEFAULT current_date,
  descrizione     text NOT NULL DEFAULT '',
  categoria       text NOT NULL DEFAULT 'varie',
  da_id           uuid NOT NULL REFERENCES vg_partecipanti(id) ON DELETE RESTRICT,
  per_chi         text CHECK (per_chi IN ('entrambi', 'uno')),
  beneficiario_id uuid REFERENCES vg_partecipanti(id) ON DELETE RESTRICT,
  -- il percorso nel bucket `vg-scontrini`; la foto la carica la Edge Function
  scontrino_path  text,
  -- quanto ci aveva letto ML Kit: resta scritto accanto a quanto è stato
  -- confermato a mano, così si vede quando la lettura ha sbagliato
  scontrino_letto numeric(12,2),
  stato           text NOT NULL DEFAULT 'in_attesa'
                    CHECK (stato IN ('in_attesa', 'confermata', 'cancellazione_richiesta', 'cancellata')),
  creata_da       uuid NOT NULL REFERENCES vg_partecipanti(id) ON DELETE RESTRICT,
  confermata_da   uuid REFERENCES vg_partecipanti(id) ON DELETE SET NULL,
  confermata_at   timestamptz,
  canc_chiesta_da uuid REFERENCES vg_partecipanti(id) ON DELETE SET NULL,
  canc_motivo     text,
  cancellata_at   timestamptz,
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now(),
  -- Una restituzione non ha un beneficiario da scegliere (è l'altro, e basta);
  -- una spesa 'uno' senza beneficiario non direbbe di chi è.
  CONSTRAINT vg_voci_per_chi_ok CHECK (
    (tipo = 'restituzione' AND per_chi IS NULL AND beneficiario_id IS NULL)
    OR (tipo = 'spesa' AND (
          (per_chi = 'entrambi' AND beneficiario_id IS NULL)
       OR (per_chi = 'uno'      AND beneficiario_id IS NOT NULL)))
  )
);
CREATE INDEX IF NOT EXISTS idx_vg_voci_viaggio ON vg_voci(viaggio_id, data DESC, created_at DESC);
ALTER TABLE vg_voci ENABLE ROW LEVEL SECURITY;

CREATE OR REPLACE FUNCTION vg_voci_touch()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  NEW.updated_at := now();
  RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS trg_vg_voci_touch ON vg_voci;
CREATE TRIGGER trg_vg_voci_touch
  BEFORE UPDATE ON vg_voci
  FOR EACH ROW EXECUTE FUNCTION vg_voci_touch();

-- ---------------------------------------------------------------------------
-- 4. vg_log — tutto quello che è successo, in ordine.
--    ⚠️ `voce_id` è ON DELETE SET NULL e la riga porta con sé descrizione e
--    importo: il registro deve restare leggibile anche se la voce di cui parla
--    non c'è più. È la stessa scelta di `ob_action_history.action_title`.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS vg_log (
  id           bigserial PRIMARY KEY,
  viaggio_id   uuid NOT NULL REFERENCES vg_viaggi(id) ON DELETE CASCADE,
  voce_id      uuid REFERENCES vg_voci(id) ON DELETE SET NULL,
  azione       text NOT NULL,
  chi_id       uuid REFERENCES vg_partecipanti(id) ON DELETE SET NULL,
  chi_nome     text NOT NULL DEFAULT '',
  voce_testo   text NOT NULL DEFAULT '',
  voce_importo numeric(12,2),
  dettaglio    text NOT NULL DEFAULT '',
  at           timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_vg_log_viaggio ON vg_log(viaggio_id, at DESC);
ALTER TABLE vg_log ENABLE ROW LEVEL SECURITY;

-- ---------------------------------------------------------------------------
-- 5. I due generatori di stringhe
--    Alfabeto senza 0/O/1/I/L: il codice si copia guardandolo da uno schermo e
--    digitandolo su un altro, dove quelle coppie si sbagliano sempre.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION vg_stringa(p_len int, p_gruppi boolean DEFAULT false)
RETURNS text
LANGUAGE plpgsql VOLATILE
AS $$
DECLARE
  v_alpha text := 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
  v_out   text := '';
  i       int;
BEGIN
  FOR i IN 1..p_len LOOP
    v_out := v_out || substr(v_alpha, 1 + floor(random() * length(v_alpha))::int, 1);
    IF p_gruppi AND i % 4 = 0 AND i < p_len THEN v_out := v_out || '-'; END IF;
  END LOOP;
  RETURN v_out;
END;
$$;

-- ---------------------------------------------------------------------------
-- 6. vg_chi — risolve il token nel partecipante. Interna: restituisce righe di
--    una tabella che nessun client deve poter leggere.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION vg_chi(p_token text)
RETURNS vg_partecipanti
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_p vg_partecipanti%ROWTYPE;
BEGIN
  SELECT * INTO v_p FROM vg_partecipanti
   WHERE token = upper(btrim(COALESCE(p_token, '')));
  IF NOT FOUND THEN RETURN NULL; END IF;
  UPDATE vg_partecipanti SET last_seen_at = now() WHERE id = v_p.id;
  RETURN v_p;
END;
$$;

/** L'altro. Con due partecipanti è una domanda che ha sempre una risposta sola. */
CREATE OR REPLACE FUNCTION vg_altro(p_viaggio uuid, p_io uuid)
RETURNS vg_partecipanti
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = public
AS $$
  SELECT * FROM vg_partecipanti WHERE viaggio_id = p_viaggio AND id <> p_io LIMIT 1;
$$;

/** Una riga di registro. Prende testo e importo dalla voce: il log deve
    restare leggibile anche quando quella riga non c'è più. */
CREATE OR REPLACE FUNCTION vg_scrivi_log(
  p_viaggio uuid, p_voce uuid, p_azione text,
  p_chi vg_partecipanti, p_dettaglio text DEFAULT ''
)
RETURNS void
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_testo   text := '';
  v_importo numeric(12,2);
BEGIN
  IF p_voce IS NOT NULL THEN
    SELECT COALESCE(NULLIF(btrim(descrizione), ''), categoria), importo
      INTO v_testo, v_importo
      FROM vg_voci WHERE id = p_voce;
  END IF;
  INSERT INTO vg_log (viaggio_id, voce_id, azione, chi_id, chi_nome, voce_testo, voce_importo, dettaglio)
  VALUES (p_viaggio, p_voce, p_azione, p_chi.id, COALESCE(p_chi.nome, ''),
          COALESCE(v_testo, ''), v_importo, COALESCE(p_dettaglio, ''));
END;
$$;

-- ---------------------------------------------------------------------------
-- 7. vg_crea / vg_entra — l'accoppiamento dei due telefoni
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION vg_crea(
  p_viaggio text,
  p_nome    text,
  p_inizio  date DEFAULT NULL,
  p_fine    date DEFAULT NULL,
  p_valuta  text DEFAULT 'EUR'
)
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_codice text;
  v_token  text;
  v_id     uuid;
  v_p      vg_partecipanti%ROWTYPE;
BEGIN
  IF btrim(COALESCE(p_viaggio, '')) = '' OR btrim(COALESCE(p_nome, '')) = '' THEN
    RETURN jsonb_build_object('ok', false, 'error', 'Servono il nome del viaggio e il tuo nome');
  END IF;

  LOOP
    v_codice := vg_stringa(8, true);
    EXIT WHEN NOT EXISTS (SELECT 1 FROM vg_viaggi WHERE codice = v_codice);
  END LOOP;
  LOOP
    v_token := vg_stringa(24);
    EXIT WHEN NOT EXISTS (SELECT 1 FROM vg_partecipanti WHERE token = v_token);
  END LOOP;

  INSERT INTO vg_viaggi (nome, data_inizio, data_fine, valuta, codice)
  VALUES (btrim(p_viaggio), p_inizio, p_fine, COALESCE(NULLIF(btrim(p_valuta), ''), 'EUR'), v_codice)
  RETURNING id INTO v_id;

  INSERT INTO vg_partecipanti (viaggio_id, nome, token)
  VALUES (v_id, btrim(p_nome), v_token)
  RETURNING * INTO v_p;

  PERFORM vg_scrivi_log(v_id, NULL, 'viaggio_creato', v_p, btrim(p_viaggio));

  RETURN jsonb_build_object('ok', true, 'token', v_token, 'codice', v_codice, 'viaggio_id', v_id);
END;
$$;

/**
 * Entra in un viaggio col codice.
 *
 * ⚠️ Se il viaggio ha già due partecipanti e il nome è quello di uno dei due,
 * si RIENTRA: si riceve un token nuovo per quella stessa persona. È la via per
 * il telefono cambiato o l'app reinstallata — senza, quel viaggio resterebbe
 * chiuso per sempre. Il prezzo è dichiarato: il codice è il segreto condiviso,
 * quindi chi ce l'ha può presentarsi come uno dei due. Fra due persone che
 * dividono le spese di un viaggio è la stessa fiducia che si danno col conto.
 */
CREATE OR REPLACE FUNCTION vg_entra(p_codice text, p_nome text)
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_v      vg_viaggi%ROWTYPE;
  v_p      vg_partecipanti%ROWTYPE;
  v_quanti int;
  v_token  text;
  v_nome   text := btrim(COALESCE(p_nome, ''));
BEGIN
  IF v_nome = '' THEN
    RETURN jsonb_build_object('ok', false, 'error', 'Scrivi il tuo nome');
  END IF;

  SELECT * INTO v_v FROM vg_viaggi
   WHERE codice = upper(btrim(COALESCE(p_codice, '')));
  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'error', 'Codice non valido');
  END IF;

  LOOP
    v_token := vg_stringa(24);
    EXIT WHEN NOT EXISTS (SELECT 1 FROM vg_partecipanti WHERE token = v_token);
  END LOOP;

  SELECT * INTO v_p FROM vg_partecipanti
   WHERE viaggio_id = v_v.id AND lower(nome) = lower(v_nome);
  IF FOUND THEN
    UPDATE vg_partecipanti SET token = v_token WHERE id = v_p.id RETURNING * INTO v_p;
    PERFORM vg_scrivi_log(v_v.id, NULL, 'rientrato', v_p, '');
    RETURN jsonb_build_object('ok', true, 'token', v_token, 'viaggio_id', v_v.id, 'rientro', true);
  END IF;

  SELECT COUNT(*) INTO v_quanti FROM vg_partecipanti WHERE viaggio_id = v_v.id;
  IF v_quanti >= 2 THEN
    RETURN jsonb_build_object('ok', false,
      'error', 'Il viaggio ha già due partecipanti. Per rientrare scrivi il nome con cui ti eri registrato.');
  END IF;

  INSERT INTO vg_partecipanti (viaggio_id, nome, token)
  VALUES (v_v.id, v_nome, v_token)
  RETURNING * INTO v_p;

  PERFORM vg_scrivi_log(v_v.id, NULL, 'entrato', v_p, '');

  RETURN jsonb_build_object('ok', true, 'token', v_token, 'viaggio_id', v_v.id, 'rientro', false);
END;
$$;

-- ---------------------------------------------------------------------------
-- 8. vg_stato — tutto quello che serve alla schermata, in una chiamata sola.
--
--    ⚠️ Il SALDO si calcola qui e non nei due telefoni: è il numero per cui
--    l'app esiste, e due implementazioni della stessa divisione sono due debiti
--    diversi il giorno che una delle due cambia.
--
--    Contano le sole voci CONFERMATE. Quelle in attesa si contano a parte
--    (`saldo_atteso`): un debito che si muove prima che l'altro abbia detto sì
--    non è un debito, è una proposta — ma nasconderla del tutto farebbe
--    sembrare fermo un conto che sta per cambiare.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION vg_saldo(p_viaggio uuid, p_io uuid, p_stati text[])
RETURNS numeric
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = public
AS $$
  SELECT COALESCE(SUM(
    CASE
      -- una spesa per entrambi: metà l'ha anticipata per l'altro
      WHEN v.tipo = 'spesa' AND v.per_chi = 'entrambi' AND v.da_id = p_io THEN  v.importo / 2
      WHEN v.tipo = 'spesa' AND v.per_chi = 'entrambi'                    THEN -v.importo / 2
      -- una spesa per una persona sola: pesa tutta, e solo se l'ha pagata l'altro
      WHEN v.tipo = 'spesa' AND v.per_chi = 'uno' AND v.da_id = p_io
           AND v.beneficiario_id <> p_io                                  THEN  v.importo
      WHEN v.tipo = 'spesa' AND v.per_chi = 'uno' AND v.da_id <> p_io
           AND v.beneficiario_id = p_io                                   THEN -v.importo
      -- restituzione: chi dà, riduce il proprio credito
      WHEN v.tipo = 'restituzione' AND v.da_id = p_io                     THEN -v.importo
      WHEN v.tipo = 'restituzione'                                        THEN  v.importo
      ELSE 0
    END
  ), 0)::numeric(12,2)
  FROM vg_voci v
  WHERE v.viaggio_id = p_viaggio AND v.stato = ANY(p_stati);
$$;

CREATE OR REPLACE FUNCTION vg_stato(p_token text, p_log_max int DEFAULT 100)
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_io    vg_partecipanti%ROWTYPE := vg_chi(p_token);
  v_altro vg_partecipanti%ROWTYPE;
  v_v     vg_viaggi%ROWTYPE;
  v_voci  jsonb;
  v_log   jsonb;
BEGIN
  IF v_io.id IS NULL THEN
    RETURN jsonb_build_object('ok', false, 'error', 'Codice non valido');
  END IF;
  SELECT * INTO v_v     FROM vg_viaggi WHERE id = v_io.viaggio_id;
  SELECT * INTO v_altro FROM vg_altro(v_io.viaggio_id, v_io.id);

  SELECT COALESCE(jsonb_agg(x ORDER BY x.data DESC, x.created_at DESC), '[]'::jsonb) INTO v_voci
  FROM (
    SELECT v.id, v.tipo, v.importo, v.data, v.descrizione, v.categoria,
           v.da_id, pd.nome AS da_nome,
           v.per_chi, v.beneficiario_id, pb.nome AS beneficiario_nome,
           v.scontrino_path, v.scontrino_letto, v.stato,
           v.creata_da, pc.nome AS creata_da_nome,
           v.confermata_da, v.confermata_at,
           v.canc_chiesta_da, v.canc_motivo, v.cancellata_at,
           v.created_at, v.updated_at
    FROM vg_voci v
    JOIN vg_partecipanti pd ON pd.id = v.da_id
    JOIN vg_partecipanti pc ON pc.id = v.creata_da
    LEFT JOIN vg_partecipanti pb ON pb.id = v.beneficiario_id
    WHERE v.viaggio_id = v_io.viaggio_id
  ) x;

  SELECT COALESCE(jsonb_agg(l ORDER BY l.at DESC), '[]'::jsonb) INTO v_log
  FROM (
    SELECT id, voce_id, azione, chi_nome, voce_testo, voce_importo, dettaglio, at
    FROM vg_log WHERE viaggio_id = v_io.viaggio_id
    ORDER BY at DESC LIMIT GREATEST(COALESCE(p_log_max, 100), 1)
  ) l;

  RETURN jsonb_build_object(
    'ok', true,
    'viaggio', jsonb_build_object('id', v_v.id, 'nome', v_v.nome, 'codice', v_v.codice,
                                  'data_inizio', v_v.data_inizio, 'data_fine', v_v.data_fine,
                                  'valuta', v_v.valuta, 'chiuso', v_v.chiuso),
    'io',    jsonb_build_object('id', v_io.id, 'nome', v_io.nome),
    'altro', CASE WHEN v_altro.id IS NULL THEN NULL
                  ELSE jsonb_build_object('id', v_altro.id, 'nome', v_altro.nome) END,
    'voci', v_voci,
    'log',  v_log,
    'saldo',        vg_saldo(v_io.viaggio_id, v_io.id, ARRAY['confermata']),
    'saldo_atteso', vg_saldo(v_io.viaggio_id, v_io.id,
                             ARRAY['confermata', 'in_attesa', 'cancellazione_richiesta']),
    'totale_viaggio', COALESCE((SELECT SUM(importo) FROM vg_voci
                                 WHERE viaggio_id = v_io.viaggio_id
                                   AND tipo = 'spesa' AND stato = 'confermata'), 0),
    'da_confermare', (SELECT COUNT(*) FROM vg_voci
                       WHERE viaggio_id = v_io.viaggio_id
                         AND creata_da <> v_io.id
                         AND stato IN ('in_attesa', 'cancellazione_richiesta')),
    'server_time', now()
  );
END;
$$;

-- ---------------------------------------------------------------------------
-- 9. vg_voce_salva — scrive una voce nuova o corregge una in attesa.
--    ⚠️ Una voce CONFERMATA non si modifica, punto: è il patto fra i due.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION vg_voce_salva(
  p_token         text,
  p_voce_id       uuid,
  p_tipo          text,
  p_importo       numeric,
  p_data          date,
  p_descrizione   text DEFAULT '',
  p_categoria     text DEFAULT 'varie',
  p_da_id         uuid DEFAULT NULL,
  p_per_chi       text DEFAULT 'entrambi',
  p_beneficiario  uuid DEFAULT NULL,
  p_scontrino     text DEFAULT NULL,
  p_letto         numeric DEFAULT NULL
)
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_io    vg_partecipanti%ROWTYPE := vg_chi(p_token);
  v_altro vg_partecipanti%ROWTYPE;
  v_vecchia vg_voci%ROWTYPE;
  v_da    uuid;
  v_ben   uuid;
  v_per   text;
  v_id    uuid;
BEGIN
  IF v_io.id IS NULL THEN
    RETURN jsonb_build_object('ok', false, 'error', 'Codice non valido');
  END IF;
  IF p_tipo NOT IN ('spesa', 'restituzione') THEN
    RETURN jsonb_build_object('ok', false, 'error', 'Tipo sconosciuto');
  END IF;
  IF p_importo IS NULL OR p_importo <= 0 THEN
    RETURN jsonb_build_object('ok', false, 'error', 'L''importo dev''essere maggiore di zero');
  END IF;

  SELECT * INTO v_altro FROM vg_altro(v_io.viaggio_id, v_io.id);

  -- Chi ha messo i soldi: di partenza io, ma si può dire che l'ha pagata l'altro.
  v_da := COALESCE(p_da_id, v_io.id);
  IF NOT EXISTS (SELECT 1 FROM vg_partecipanti WHERE id = v_da AND viaggio_id = v_io.viaggio_id) THEN
    RETURN jsonb_build_object('ok', false, 'error', 'Persona sconosciuta');
  END IF;

  IF p_tipo = 'restituzione' THEN
    IF v_altro.id IS NULL THEN
      RETURN jsonb_build_object('ok', false, 'error', 'Una restituzione ha bisogno di due partecipanti');
    END IF;
    v_per := NULL; v_ben := NULL;
  ELSE
    v_per := COALESCE(NULLIF(btrim(p_per_chi), ''), 'entrambi');
    IF v_per NOT IN ('entrambi', 'uno') THEN
      RETURN jsonb_build_object('ok', false, 'error', '«Per chi» non valido');
    END IF;
    IF v_per = 'entrambi' AND v_altro.id IS NULL THEN
      RETURN jsonb_build_object('ok', false, 'error',
        'Finché sei da solo nel viaggio una spesa non si può dividere: aspetta che l''altro entri col codice');
    END IF;
    v_ben := CASE WHEN v_per = 'uno' THEN COALESCE(p_beneficiario, v_da) END;
    IF v_per = 'uno' AND NOT EXISTS (
         SELECT 1 FROM vg_partecipanti WHERE id = v_ben AND viaggio_id = v_io.viaggio_id) THEN
      RETURN jsonb_build_object('ok', false, 'error', 'Persona sconosciuta');
    END IF;
  END IF;

  IF p_voce_id IS NULL THEN
    INSERT INTO vg_voci (viaggio_id, tipo, importo, data, descrizione, categoria,
                         da_id, per_chi, beneficiario_id, scontrino_path, scontrino_letto, creata_da)
    VALUES (v_io.viaggio_id, p_tipo, round(p_importo, 2), COALESCE(p_data, current_date),
            COALESCE(btrim(p_descrizione), ''), COALESCE(NULLIF(btrim(p_categoria), ''), 'varie'),
            v_da, v_per, v_ben, p_scontrino, p_letto, v_io.id)
    RETURNING id INTO v_id;
    PERFORM vg_scrivi_log(v_io.viaggio_id, v_id, 'creata', v_io, '');
    RETURN jsonb_build_object('ok', true, 'id', v_id, 'creata', true);
  END IF;

  SELECT * INTO v_vecchia FROM vg_voci WHERE id = p_voce_id AND viaggio_id = v_io.viaggio_id;
  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'error', 'Voce inesistente');
  END IF;
  IF v_vecchia.stato <> 'in_attesa' THEN
    RETURN jsonb_build_object('ok', false, 'error',
      CASE v_vecchia.stato
        WHEN 'confermata' THEN 'La voce è già stata confermata: non si modifica più'
        WHEN 'cancellazione_richiesta' THEN 'C''è una richiesta di cancellazione in corso'
        ELSE 'La voce è stata cancellata' END);
  END IF;
  IF v_vecchia.creata_da <> v_io.id THEN
    RETURN jsonb_build_object('ok', false, 'error', 'La voce l''ha scritta l''altro: puoi solo confermarla');
  END IF;

  UPDATE vg_voci SET
    tipo = p_tipo, importo = round(p_importo, 2), data = COALESCE(p_data, current_date),
    descrizione = COALESCE(btrim(p_descrizione), ''),
    categoria = COALESCE(NULLIF(btrim(p_categoria), ''), 'varie'),
    da_id = v_da, per_chi = v_per, beneficiario_id = v_ben,
    scontrino_path = COALESCE(p_scontrino, scontrino_path),
    scontrino_letto = COALESCE(p_letto, scontrino_letto)
  WHERE id = v_vecchia.id;

  PERFORM vg_scrivi_log(v_io.viaggio_id, v_vecchia.id, 'modificata', v_io,
                        CASE WHEN v_vecchia.importo <> round(p_importo, 2)
                             THEN 'da ' || to_char(v_vecchia.importo, 'FM999999990.00') || ' €'
                             ELSE '' END);

  RETURN jsonb_build_object('ok', true, 'id', v_vecchia.id, 'creata', false);
END;
$$;

-- ---------------------------------------------------------------------------
-- 10. Il giro delle conferme
-- ---------------------------------------------------------------------------
/** Conferma. La può dare SOLO l'altro: una voce confermata da chi l'ha scritta
    non sarebbe una conferma, sarebbe un salvataggio con un altro nome. */
CREATE OR REPLACE FUNCTION vg_voce_conferma(p_token text, p_voce_id uuid)
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_io vg_partecipanti%ROWTYPE := vg_chi(p_token);
  v_v  vg_voci%ROWTYPE;
BEGIN
  IF v_io.id IS NULL THEN
    RETURN jsonb_build_object('ok', false, 'error', 'Codice non valido');
  END IF;
  SELECT * INTO v_v FROM vg_voci WHERE id = p_voce_id AND viaggio_id = v_io.viaggio_id;
  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'error', 'Voce inesistente');
  END IF;
  IF v_v.stato <> 'in_attesa' THEN
    RETURN jsonb_build_object('ok', false, 'error', 'Questa voce non è in attesa di conferma');
  END IF;
  IF v_v.creata_da = v_io.id THEN
    RETURN jsonb_build_object('ok', false, 'error', 'La conferma la dà l''altro, non chi ha scritto la voce');
  END IF;

  UPDATE vg_voci SET stato = 'confermata', confermata_da = v_io.id, confermata_at = now()
   WHERE id = v_v.id;
  PERFORM vg_scrivi_log(v_io.viaggio_id, v_v.id, 'confermata', v_io, '');
  RETURN jsonb_build_object('ok', true);
END;
$$;

/** Toglie una voce ancora in attesa. Solo chi l'ha scritta, e finché nessuno
    l'ha confermata: dopo si passa dalla richiesta. La riga resta, `cancellata`. */
CREATE OR REPLACE FUNCTION vg_voce_elimina(p_token text, p_voce_id uuid)
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_io vg_partecipanti%ROWTYPE := vg_chi(p_token);
  v_v  vg_voci%ROWTYPE;
BEGIN
  IF v_io.id IS NULL THEN
    RETURN jsonb_build_object('ok', false, 'error', 'Codice non valido');
  END IF;
  SELECT * INTO v_v FROM vg_voci WHERE id = p_voce_id AND viaggio_id = v_io.viaggio_id;
  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'error', 'Voce inesistente');
  END IF;
  IF v_v.stato <> 'in_attesa' THEN
    RETURN jsonb_build_object('ok', false, 'error',
      'La voce non è più in attesa: per toglierla serve il consenso dell''altro');
  END IF;
  IF v_v.creata_da <> v_io.id THEN
    RETURN jsonb_build_object('ok', false, 'error', 'La voce l''ha scritta l''altro');
  END IF;

  PERFORM vg_scrivi_log(v_io.viaggio_id, v_v.id, 'eliminata', v_io, '');
  UPDATE vg_voci SET stato = 'cancellata', cancellata_at = now() WHERE id = v_v.id;
  RETURN jsonb_build_object('ok', true);
END;
$$;

/** Chiede all'altro di cancellare una voce confermata. */
CREATE OR REPLACE FUNCTION vg_voce_chiedi_cancellazione(p_token text, p_voce_id uuid, p_motivo text DEFAULT '')
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_io vg_partecipanti%ROWTYPE := vg_chi(p_token);
  v_v  vg_voci%ROWTYPE;
BEGIN
  IF v_io.id IS NULL THEN
    RETURN jsonb_build_object('ok', false, 'error', 'Codice non valido');
  END IF;
  SELECT * INTO v_v FROM vg_voci WHERE id = p_voce_id AND viaggio_id = v_io.viaggio_id;
  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'error', 'Voce inesistente');
  END IF;
  IF v_v.stato <> 'confermata' THEN
    RETURN jsonb_build_object('ok', false, 'error', 'Si chiede la cancellazione di una voce confermata');
  END IF;

  UPDATE vg_voci SET stato = 'cancellazione_richiesta',
                     canc_chiesta_da = v_io.id,
                     canc_motivo = COALESCE(btrim(p_motivo), '')
   WHERE id = v_v.id;
  PERFORM vg_scrivi_log(v_io.viaggio_id, v_v.id, 'cancellazione_chiesta', v_io, COALESCE(btrim(p_motivo), ''));
  RETURN jsonb_build_object('ok', true);
END;
$$;

/** L'altro decide. Approvata: la voce esce dal saldo e resta a schermo barrata.
    Rifiutata: torna confermata, e il registro lo dice. */
CREATE OR REPLACE FUNCTION vg_voce_risolvi_cancellazione(p_token text, p_voce_id uuid, p_approva boolean)
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_io vg_partecipanti%ROWTYPE := vg_chi(p_token);
  v_v  vg_voci%ROWTYPE;
BEGIN
  IF v_io.id IS NULL THEN
    RETURN jsonb_build_object('ok', false, 'error', 'Codice non valido');
  END IF;
  SELECT * INTO v_v FROM vg_voci WHERE id = p_voce_id AND viaggio_id = v_io.viaggio_id;
  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'error', 'Voce inesistente');
  END IF;
  IF v_v.stato <> 'cancellazione_richiesta' THEN
    RETURN jsonb_build_object('ok', false, 'error', 'Nessuna richiesta di cancellazione su questa voce');
  END IF;
  IF v_v.canc_chiesta_da = v_io.id THEN
    RETURN jsonb_build_object('ok', false, 'error', 'La richiesta l''hai fatta tu: decide l''altro');
  END IF;

  IF p_approva THEN
    PERFORM vg_scrivi_log(v_io.viaggio_id, v_v.id, 'cancellazione_approvata', v_io, '');
    UPDATE vg_voci SET stato = 'cancellata', cancellata_at = now() WHERE id = v_v.id;
  ELSE
    PERFORM vg_scrivi_log(v_io.viaggio_id, v_v.id, 'cancellazione_rifiutata', v_io, '');
    UPDATE vg_voci SET stato = 'confermata', canc_chiesta_da = NULL, canc_motivo = NULL WHERE id = v_v.id;
  END IF;
  RETURN jsonb_build_object('ok', true, 'approvata', p_approva);
END;
$$;

-- ---------------------------------------------------------------------------
-- 11. Lo scontrino
--     La foto NON passa da PostgREST: la carica la Edge Function `tandem-foto`
--     col service role, in un bucket privato. Queste due funzioni sono il suo
--     unico modo di sapere di chi è il token che le è arrivato, e restano
--     eseguibili dal solo service role.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION vg_viaggio_del_token(p_token text)
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_io vg_partecipanti%ROWTYPE;
BEGIN
  SELECT * INTO v_io FROM vg_partecipanti WHERE token = upper(btrim(COALESCE(p_token, '')));
  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'error', 'Codice non valido');
  END IF;
  RETURN jsonb_build_object('ok', true, 'viaggio_id', v_io.viaggio_id,
                            'partecipante_id', v_io.id, 'nome', v_io.nome);
END;
$$;

/** Il percorso della foto di una voce, per la Edge Function che deve firmarne
    l'URL: la voce dev'essere del viaggio di quel token, o un id qualsiasi
    aprirebbe lo scontrino di un altro viaggio. */
CREATE OR REPLACE FUNCTION vg_scontrino_del_token(p_token text, p_voce_id uuid)
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_io   vg_partecipanti%ROWTYPE;
  v_path text;
BEGIN
  SELECT * INTO v_io FROM vg_partecipanti WHERE token = upper(btrim(COALESCE(p_token, '')));
  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'error', 'Codice non valido');
  END IF;
  SELECT scontrino_path INTO v_path FROM vg_voci
   WHERE id = p_voce_id AND viaggio_id = v_io.viaggio_id;
  IF v_path IS NULL THEN
    RETURN jsonb_build_object('ok', false, 'error', 'Nessuno scontrino');
  END IF;
  RETURN jsonb_build_object('ok', true, 'path', v_path);
END;
$$;

-- ---------------------------------------------------------------------------
-- 12. I permessi
--     Le RPC dell'APK sono raggiungibili dalla anon key: il controllo è il
--     token del telefono, non il ruolo — è la stessa scelta di sos_*. Quelle
--     che restituiscono righe di vg_partecipanti (token compreso) o che
--     servono alla sola Edge Function restano fuori dalla portata dei client.
-- ---------------------------------------------------------------------------
-- ⚠️ Si revoca a **PUBLIC** e non solo ad anon/authenticated, ed è la
-- differenza fra chiuso e aperto: in PostgreSQL l'EXECUTE di una funzione nasce
-- concesso a PUBLIC, e i due ruoli lo ereditano da lì — togliendolo a loro
-- soltanto, la funzione resta eseguibile e la revoca sembra fatta.
--
-- Qui non è teoria: `vg_altro` restituisce la riga dell'altro partecipante,
-- **token compreso**. Raggiungibile con la anon key, chi conosce l'id del
-- viaggio (glielo dà `vg_crea`/`vg_entra`, quindi lo conosce ognuno dei due) si
-- prenderebbe il token dell'altro — cioè potrebbe confermarsi da solo le
-- proprie voci, che è esattamente la cosa che questa app esiste per impedire.
REVOKE ALL ON FUNCTION vg_chi(text)                          FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION vg_altro(uuid, uuid)                  FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION vg_scrivi_log(uuid, uuid, text, vg_partecipanti, text) FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION vg_saldo(uuid, uuid, text[])          FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION vg_viaggio_del_token(text)            FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION vg_scontrino_del_token(text, uuid)    FROM PUBLIC, anon, authenticated;
-- Le due della Edge Function le esegue il service role, che qui deve nominarsi
-- esplicitamente: la revoca a PUBLIC ha portato via anche la sua.
GRANT EXECUTE ON FUNCTION vg_viaggio_del_token(text)         TO service_role;
GRANT EXECUTE ON FUNCTION vg_scontrino_del_token(text, uuid) TO service_role;

GRANT EXECUTE ON FUNCTION vg_crea(text, text, date, date, text)      TO anon, authenticated;
GRANT EXECUTE ON FUNCTION vg_entra(text, text)                       TO anon, authenticated;
GRANT EXECUTE ON FUNCTION vg_stato(text, int)                        TO anon, authenticated;
GRANT EXECUTE ON FUNCTION vg_voce_salva(text, uuid, text, numeric, date, text, text, uuid, text, uuid, text, numeric) TO anon, authenticated;
GRANT EXECUTE ON FUNCTION vg_voce_conferma(text, uuid)               TO anon, authenticated;
GRANT EXECUTE ON FUNCTION vg_voce_elimina(text, uuid)                TO anon, authenticated;
GRANT EXECUTE ON FUNCTION vg_voce_chiedi_cancellazione(text, uuid, text)     TO anon, authenticated;
GRANT EXECUTE ON FUNCTION vg_voce_risolvi_cancellazione(text, uuid, boolean) TO anon, authenticated;

-- ---------------------------------------------------------------------------
-- 13. Il bucket degli scontrini — privato.
--     Nessuna policy: ci scrive e ci legge la sola Edge Function col service
--     role, che è anche l'unica a sapere in quale cartella sta un viaggio.
-- ---------------------------------------------------------------------------
-- ⚠️ Se il ruolo della migration non può scrivere in storage.buckets, la
-- migration NON deve morire qui: si limita ad avvisare, perché il bucket lo
-- crea da sé anche la Edge Function al primo caricamento (`assicuraBucket`).
-- Un deploy fermo su questa riga si porterebbe dietro tutto il resto.
DO $$
BEGIN
  INSERT INTO storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
  VALUES ('vg-scontrini', 'vg-scontrini', false, 8388608,
          ARRAY['image/jpeg', 'image/png', 'image/webp'])
  ON CONFLICT (id) DO NOTHING;
EXCEPTION WHEN OTHERS THEN
  RAISE WARNING 'bucket vg-scontrini non creato dalla migration (%): lo creerà la Edge Function', SQLERRM;
END;
$$;

-- ⚠️ Nessuna riga in `cm_apps`: Tandem non è una bolla di AppSphere. Non ha un
-- gemello web, non ha un punteggio, e i suoi dati non appartengono a un
-- account — è un'APK a sé che si apre col codice del viaggio.
