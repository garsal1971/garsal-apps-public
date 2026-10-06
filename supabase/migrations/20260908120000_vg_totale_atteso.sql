-- ---------------------------------------------------------------------------
-- «Spese in giro» — «Speso in tutto» accanto al totale che comprende le voci
-- in attesa.
--
-- Il difetto: nello stesso riquadro convivevano due misure diverse senza che
-- si vedesse. La riga del saldo dichiara di comprendere il non confermato
-- («Fabiano ti dovrebbe 314,61 €»), quella sotto no — e con due voci ancora
-- in attesa diceva «Speso in tutto 0,00 €». Corretto per come era scritto, e
-- illeggibile: un viaggio da 579,61 € che dichiara di non aver speso niente.
--
-- Il rimedio è quello che il saldo ha già: due numeri accanto, ciascuno
-- etichettato per quel che è. `saldo` / `saldo_atteso` diventano quindi
-- `totale_viaggio` / `totale_atteso`.
--
-- ⚠️ CREATE OR REPLACE e non DROP: la funzione tiene le sue GRANT (è
--    eseguibile dalla anon key, che è come ci parla l'APK), e un DROP le
--    porterebbe via lasciando l'app muta senza nessun errore di deploy.
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
    -- ⚠️ Il gemello di `saldo_atteso`, e per la stessa ragione: le due misure
    --    stanno accanto e si dichiarano. Con tutte le voci ancora in attesa
    --    «speso in tutto» vale zero — vero, ma sotto un saldo atteso di 314,61
    --    si legge come un errore. Le CANCELLATE restano fuori di qua e di là:
    --    una spesa tolta non è una spesa che aspetta una conferma.
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

