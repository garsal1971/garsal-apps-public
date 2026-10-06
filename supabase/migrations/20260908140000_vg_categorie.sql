-- ============================================================
-- «Spese in giro» — le categorie di spesa diventano del VIAGGIO
-- ============================================================
-- Fino alla v1.0.4 erano un elenco fisso scritto in Kotlin (`CATEGORIE` in
-- `Model.kt`): sette voci uguali per tutti, e per aggiungerne una serviva una
-- build. Ora si gestiscono da ⚙️ Impostazioni — se ne aggiunge una, si cambia
-- la descrizione a una che c'è, e si toglie una che nessuna voce usa.
--
-- ⚠️ **Stanno nel database e non nelle preferenze del telefono**, ed è la
-- ragione per cui questa migration esiste. Le voci sono condivise fra i due
-- telefoni: una categoria inventata qui e tenuta in locale, sull'altro
-- telefono comparirebbe come chiave grezza («pedaggi» invece di
-- «🛣️ Pedaggi»), e il controllo «non usata» guarderebbe solo le voci che quel
-- telefono ha già scaricato. È la stessa ragione per cui il saldo si calcola in
-- `vg_saldo` e non in Kotlin.
--
-- ⚠️ Come tutte le `vg_*`: **nessun `user_id`**, RLS accesa e **senza policy**,
-- e tutto passa dalle RPC `SECURITY DEFINER` che riconoscono chi chiama dal
-- token del suo telefono.

-- ---------------------------------------------------------------------------
-- 1. La tabella
--
-- ⚠️ `chiave` è quello che finisce in `vg_voci.categoria`, e **non si cambia
--    mai**: è il perno per cui «cambiare la descrizione» non riscrive lo
--    storico. Rinominare «Mangiare» in «Cibo e bevande» tocca una riga sola e
--    tutte le voci di quel viaggio si rileggono col nome nuovo; se invece la
--    chiave seguisse il nome, ogni voce già segnata resterebbe agganciata a
--    una categoria che non esiste più — e si vedrebbe come chiave grezza. È la
--    stessa scelta delle opzioni di una combo in Memo, che archiviano l'id e
--    mai l'etichetta.
--
-- ⚠️ `vg_voci.categoria` resta **testo libero** e senza chiave esterna: una
--    voce non deve poter sparire, né rifiutarsi di salvarsi, perché qualcuno
--    ha tolto una categoria. Una chiave che non ha più la sua riga si mostra
--    com'è scritta — la *misura tolta* dei diari di Memo.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS vg_categorie (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  viaggio_id uuid NOT NULL REFERENCES vg_viaggi(id) ON DELETE CASCADE,
  chiave     text NOT NULL,
  emoji      text NOT NULL DEFAULT '🏷️',
  nome       text NOT NULL,
  posizione  int  NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_vg_categorie_chiave UNIQUE (viaggio_id, chiave),
  CONSTRAINT vg_categorie_nome_ok CHECK (btrim(nome) <> ''),
  CONSTRAINT vg_categorie_chiave_ok CHECK (btrim(chiave) <> '')
);
CREATE INDEX IF NOT EXISTS idx_vg_categorie_viaggio ON vg_categorie(viaggio_id, posizione, nome);
ALTER TABLE vg_categorie ENABLE ROW LEVEL SECURITY;

-- ---------------------------------------------------------------------------
-- 2. La chiave si ricava dal nome, una volta sola
--
-- ⚠️ Gli accenti si traslitterano invece di diventare '_': `unaccent` è
--    un'estensione che su un database nuovo può non esserci, e una chiave
--    «caff_» sarebbe brutta ma soprattutto indistinguibile da «caffe» scritto
--    senza accento. La chiave non si legge da nessuna parte — quel che si
--    vede è `nome` — ma finisce scritta in ogni voce, quindi deve essere
--    stabile e leggibile in un dump.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION vg_chiave(p_nome text)
RETURNS text
LANGUAGE sql IMMUTABLE
AS $$
  SELECT COALESCE(NULLIF(btrim(regexp_replace(
           translate(lower(btrim(COALESCE(p_nome, ''))),
                     'àáâäãåèéêëìíîïòóôöõùúûüçñ',
                     'aaaaaaeeeeiiiiooooouuuucn'),
           '[^a-z0-9]+', '_', 'g'), '_'), ''), 'categoria');
$$;

-- ---------------------------------------------------------------------------
-- 3. Le sette di partenza, e quelle che un viaggio sta già usando
--
-- ⚠️ Le sette sono le stesse che erano scritte in Kotlin, chiave per chiave:
--    «Sardegna» ha già voci segnate `cibo` e `trasporto`, e senza questa
--    semina le leggerebbe come chiavi grezze il giorno dopo l'aggiornamento.
--
-- ⚠️ Si semina anche **ogni categoria che le voci di quel viaggio usano già** e
--    che non è fra le sette: `vg_voci.categoria` è testo libero, e una chiave
--    senza la sua riga non si potrebbe né rinominare né togliere — sarebbe
--    l'unica categoria ingestibile proprio in questa schermata.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION vg_categorie_semina(p_viaggio uuid)
RETURNS void
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
  INSERT INTO vg_categorie (viaggio_id, chiave, emoji, nome, posizione)
  SELECT p_viaggio, d.chiave, d.emoji, d.nome, d.pos
  FROM (VALUES
    ('cibo',      '🍝',  'Mangiare',           1),
    ('bar',       '☕',  'Bar e caffè',        2),
    ('alloggio',  '🛏️',  'Dormire',            3),
    ('trasporto', '🚆',  'Treni e trasporti',  4),
    ('bici',      '🔧',  'Bici e officina',    5),
    ('visite',    '🎟️',  'Visite e ingressi',  6),
    ('varie',     '🛒',  'Varie',              7)
  ) AS d(chiave, emoji, nome, pos)
  ON CONFLICT (viaggio_id, chiave) DO NOTHING;

  INSERT INTO vg_categorie (viaggio_id, chiave, emoji, nome, posizione)
  SELECT DISTINCT p_viaggio, btrim(v.categoria), '🏷️', btrim(v.categoria), 50
  FROM vg_voci v
  WHERE v.viaggio_id = p_viaggio AND btrim(COALESCE(v.categoria, '')) <> ''
  ON CONFLICT (viaggio_id, chiave) DO NOTHING;
END;
$$;

-- I viaggi che ci sono già.
DO $$
DECLARE v_id uuid;
BEGIN
  FOR v_id IN SELECT id FROM vg_viaggi LOOP
    PERFORM vg_categorie_semina(v_id);
  END LOOP;
END;
$$;

-- ---------------------------------------------------------------------------
-- 4. vg_crea — un viaggio nuovo nasce con le sue sette
--
-- ⚠️ CREATE OR REPLACE e non DROP: la funzione tiene le sue GRANT (è
--    eseguibile dalla anon key, che è come ci parla l'APK), e un DROP le
--    porterebbe via lasciando l'app muta senza nessun errore di deploy.
--    Copiata da `20260908100000_vg_tandem_viaggio_bici.sql` con la sola
--    aggiunta della semina.
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

  PERFORM vg_categorie_semina(v_id);
  PERFORM vg_scrivi_log(v_id, NULL, 'viaggio_creato', v_p, btrim(p_viaggio));

  RETURN jsonb_build_object('ok', true, 'token', v_token, 'codice', v_codice, 'viaggio_id', v_id);
END;
$$;

-- ---------------------------------------------------------------------------
-- 5. Aggiungere e rinominare
--
-- ⚠️ Si cambiano **emoji e nome, mai la chiave**: vedi il punto 1. Il form non
--    la mostra affatto — un campo che non si può cambiare è un campo che non
--    si mette.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION vg_categoria_salva(
  p_token text,
  p_id    uuid,
  p_emoji text,
  p_nome  text
)
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_io     vg_partecipanti%ROWTYPE := vg_chi(p_token);
  v_c      vg_categorie%ROWTYPE;
  v_nome   text := btrim(COALESCE(p_nome, ''));
  v_emoji  text := NULLIF(btrim(COALESCE(p_emoji, '')), '');
  v_chiave text;
  v_base   text;
  i        int := 1;
BEGIN
  IF v_io.id IS NULL THEN
    RETURN jsonb_build_object('ok', false, 'error', 'Codice non valido');
  END IF;
  IF v_nome = '' THEN
    RETURN jsonb_build_object('ok', false, 'error', 'Serve il nome della categoria');
  END IF;
  IF length(v_nome) > 40 THEN
    RETURN jsonb_build_object('ok', false, 'error', 'Il nome è troppo lungo: al massimo 40 caratteri');
  END IF;
  -- Un'emoji e non una seconda descrizione: nella tendina e sulla scheda della
  -- voce sta accanto al nome, e più di un paio di caratteri lo spingerebbe fuori.
  IF v_emoji IS NOT NULL AND length(v_emoji) > 8 THEN
    RETURN jsonb_build_object('ok', false, 'error', 'Nell''emoji ci sta un simbolo solo');
  END IF;

  IF p_id IS NULL THEN
    v_base := vg_chiave(v_nome);
    v_chiave := v_base;
    WHILE EXISTS (SELECT 1 FROM vg_categorie
                   WHERE viaggio_id = v_io.viaggio_id AND chiave = v_chiave) LOOP
      i := i + 1;
      v_chiave := v_base || '_' || i;
    END LOOP;

    INSERT INTO vg_categorie (viaggio_id, chiave, emoji, nome, posizione)
    VALUES (v_io.viaggio_id, v_chiave, COALESCE(v_emoji, '🏷️'), v_nome,
            COALESCE((SELECT MAX(posizione) FROM vg_categorie
                       WHERE viaggio_id = v_io.viaggio_id), 0) + 1)
    RETURNING * INTO v_c;

    PERFORM vg_scrivi_log(v_io.viaggio_id, NULL, 'categoria_creata', v_io,
                          v_c.emoji || ' ' || v_c.nome);
  ELSE
    SELECT * INTO v_c FROM vg_categorie
     WHERE id = p_id AND viaggio_id = v_io.viaggio_id;
    IF v_c.id IS NULL THEN
      RETURN jsonb_build_object('ok', false, 'error', 'Categoria non trovata in questo viaggio');
    END IF;

    UPDATE vg_categorie
       SET emoji = COALESCE(v_emoji, v_c.emoji), nome = v_nome
     WHERE id = v_c.id
    RETURNING * INTO v_c;

    PERFORM vg_scrivi_log(v_io.viaggio_id, NULL, 'categoria_cambiata', v_io,
                          v_c.emoji || ' ' || v_c.nome);
  END IF;

  RETURN jsonb_build_object('ok', true, 'id', v_c.id, 'chiave', v_c.chiave);
END;
$$;

-- ---------------------------------------------------------------------------
-- 6. Togliere — solo una che non serve a nessuna voce
--
-- ⚠️ Le voci **cancellate contano**: restano a schermo, barrate, e continuano
--    a mostrare la loro categoria. Toglierla lascerebbe lì una chiave grezza in
--    una riga che nessuno può più correggere, perché una voce cancellata non si
--    modifica.
--
-- ⚠️ L'ultima non si toglie: senza nessuna categoria il form di una spesa non
--    avrebbe niente da scegliere — sarebbe rotto, non vuoto.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION vg_categoria_elimina(p_token text, p_id uuid)
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_io   vg_partecipanti%ROWTYPE := vg_chi(p_token);
  v_c    vg_categorie%ROWTYPE;
  v_usi  int;
  v_vive int;
BEGIN
  IF v_io.id IS NULL THEN
    RETURN jsonb_build_object('ok', false, 'error', 'Codice non valido');
  END IF;

  SELECT * INTO v_c FROM vg_categorie
   WHERE id = p_id AND viaggio_id = v_io.viaggio_id;
  IF v_c.id IS NULL THEN
    RETURN jsonb_build_object('ok', false, 'error', 'Categoria non trovata in questo viaggio');
  END IF;

  SELECT COUNT(*), COUNT(*) FILTER (WHERE stato <> 'cancellata')
    INTO v_usi, v_vive
    FROM vg_voci
   WHERE viaggio_id = v_io.viaggio_id AND categoria = v_c.chiave;

  -- ⚠️ Il rimedio si dice solo quando c'è: una categoria rimasta a una voce
  --    **cancellata** non si può liberare — una voce cancellata non si
  --    modifica più — e «cambia categoria a quelle» manderebbe a cercare un
  --    pulsante che non esiste. Quella categoria resta, ed è giusto così:
  --    la voce resta a schermo, barrata, e deve restare leggibile.
  IF v_usi > 0 THEN
    RETURN jsonb_build_object('ok', false, 'error',
      '«' || v_c.nome || '» è usata in ' || v_usi ||
      CASE WHEN v_usi = 1 THEN ' voce' ELSE ' voci' END ||
      CASE WHEN v_vive > 0
           THEN ': cambia categoria a quelle e poi si toglie.'
           ELSE ', ormai cancellate: restano a schermo barrate, e senza la loro categoria non si leggerebbero più.'
      END);
  END IF;

  IF (SELECT COUNT(*) FROM vg_categorie WHERE viaggio_id = v_io.viaggio_id) <= 1 THEN
    RETURN jsonb_build_object('ok', false, 'error',
      'È l''ultima categoria: senza, una spesa non avrebbe niente da scegliere.');
  END IF;

  DELETE FROM vg_categorie WHERE id = v_c.id;
  PERFORM vg_scrivi_log(v_io.viaggio_id, NULL, 'categoria_tolta', v_io,
                        v_c.emoji || ' ' || v_c.nome);

  RETURN jsonb_build_object('ok', true);
END;
$$;

-- ---------------------------------------------------------------------------
-- 7. vg_stato — le categorie arrivano con tutto il resto, in una chiamata sola
--
-- ⚠️ Ogni riga porta con sé i suoi `usi`: è la stessa domanda che
--    `vg_categoria_elimina` fa prima di rifiutare, e senza di lei l'app
--    offrirebbe un 🗑 che risponde «non si può» solo dopo averlo premuto.
--    Il conto lo fa il server e non i due telefoni, che vedono le stesse voci
--    ma non necessariamente nello stesso momento.
--
-- ⚠️ CREATE OR REPLACE, per la ragione del punto 4. Copiata da
--    `20260908120000_vg_totale_atteso.sql` con la sola aggiunta di `categorie`.
-- ---------------------------------------------------------------------------
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
  v_cat   jsonb;
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

  SELECT COALESCE(jsonb_agg(c ORDER BY c.posizione, c.nome), '[]'::jsonb) INTO v_cat
  FROM (
    SELECT k.id, k.chiave, k.emoji, k.nome, k.posizione,
           (SELECT COUNT(*) FROM vg_voci v
             WHERE v.viaggio_id = k.viaggio_id AND v.categoria = k.chiave) AS usi
    FROM vg_categorie k
    WHERE k.viaggio_id = v_io.viaggio_id
  ) c;

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
    'categorie', v_cat,
    'log',  v_log,
    'saldo',        vg_saldo(v_io.viaggio_id, v_io.id, ARRAY['confermata']),
    'saldo_atteso', vg_saldo(v_io.viaggio_id, v_io.id,
                             ARRAY['confermata', 'in_attesa', 'cancellazione_richiesta']),
    'totale_viaggio', COALESCE((SELECT SUM(importo) FROM vg_voci
                                 WHERE viaggio_id = v_io.viaggio_id
                                   AND tipo = 'spesa' AND stato = 'confermata'), 0),
    'totale_atteso', COALESCE((SELECT SUM(importo) FROM vg_voci
                                WHERE viaggio_id = v_io.viaggio_id
                                  AND tipo = 'spesa'
                                  AND stato IN ('confermata', 'in_attesa',
                                                'cancellazione_richiesta')), 0),
    'da_confermare', (SELECT COUNT(*) FROM vg_voci
                       WHERE viaggio_id = v_io.viaggio_id
                         AND creata_da <> v_io.id
                         AND stato IN ('in_attesa', 'cancellazione_richiesta')),
    'server_time', now()
  );
END;
$$;

-- ---------------------------------------------------------------------------
-- 8. I permessi
--
-- ⚠️ La revoca è a PUBLIC e non ai soli `anon, authenticated`: l'EXECUTE di una
--    funzione nasce concesso a PUBLIC e i due ruoli lo ereditano da lì —
--    togliendolo a loro soltanto, la funzione resta eseguibile e la revoca
--    *sembra* fatta. `vg_categorie_semina` prende un `viaggio_id` e non un
--    token, quindi da fuori sarebbe un modo di scrivere righe nel viaggio di
--    un altro.
-- ---------------------------------------------------------------------------
REVOKE ALL ON FUNCTION vg_categorie_semina(uuid) FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION vg_chiave(text)           FROM PUBLIC, anon, authenticated;

GRANT EXECUTE ON FUNCTION vg_categoria_salva(text, uuid, text, text) TO anon, authenticated;
GRANT EXECUTE ON FUNCTION vg_categoria_elimina(text, uuid)           TO anon, authenticated;
