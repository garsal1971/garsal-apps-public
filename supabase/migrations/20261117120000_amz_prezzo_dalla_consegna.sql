-- Scorte Amazon: il prezzo a confezione della scheda è quello della CONSEGNA più recente.
--
-- Prima era la riga più recente col prezzo di qualunque tipo: un promemoria letto
-- dalle mail per una consegna poi spostata o mai arrivata dettava il prezzo.
-- Ora vengono prima, dalla più recente, le righe che dicono «è arrivata a questo
-- prezzo»: 'consegnato' e i prezzi scritti a mano nel ✏️ ('manuale', extra esclusi).
-- Senza nessuna di loro col prezzo si ripiega, come prima, sull'ultima riga col
-- prezzo di qualunque tipo. Gli extra restano fuori (20261117100000).
-- Stessa regola per il prezzo precedente (avvisi prezzo_su / prezzo_giu).
-- Il resto della funzione è identico a 20261117100000_amz_prezzo_extra_non_conta.sql.

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
       AND NOT (tipo = 'manuale' AND COALESCE(nota, '') ILIKE 'extra%')
     ORDER BY (tipo = 'consegnato' OR tipo = 'manuale') DESC, giorno DESC, created_at DESC LIMIT 1;
    SELECT prezzo INTO v_p0 FROM amz_consegne
     WHERE abbonamento_id = r.id AND prezzo IS NOT NULL
       AND NOT (tipo = 'manuale' AND COALESCE(nota, '') ILIKE 'extra%')
     ORDER BY (tipo = 'consegnato' OR tipo = 'manuale') DESC, giorno DESC, created_at DESC OFFSET 1 LIMIT 1;
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
