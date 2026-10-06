-- =============================================================================
-- Spese in giro — il segno delle restituzioni in vg_saldo era al contrario.
--
-- Il saldo è positivo quando l'altro ti deve. Chi restituisce RIDUCE il
-- proprio debito, quindi il suo saldo SALE: la restituzione va col segno +
-- per chi la dà e col segno − per chi la riceve. Prima era il contrario, e
-- restituire 40,92 € su un debito di 40,92 € lo portava a 81,84 € invece che
-- a zero.
--
-- Le voci archiviate sono giuste: cambia solo il conto, quindi il saldo si
-- corregge da sé alla prossima lettura di vg_stato, senza toccare l'APK.
-- =============================================================================

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
      -- restituzione: chi dà riduce il proprio debito (il saldo sale),
      -- chi riceve riduce il proprio credito (il saldo scende)
      WHEN v.tipo = 'restituzione' AND v.da_id = p_io                     THEN  v.importo
      WHEN v.tipo = 'restituzione'                                        THEN -v.importo
      ELSE 0
    END
  ), 0)::numeric(12,2)
  FROM vg_voci v
  WHERE v.viaggio_id = p_viaggio AND v.stato = ANY(p_stati);
$$;

-- ⚠️ A PUBLIC e non solo ad anon/authenticated: vedi la migration d'origine.
REVOKE ALL ON FUNCTION vg_saldo(uuid, uuid, text[]) FROM PUBLIC, anon, authenticated;
