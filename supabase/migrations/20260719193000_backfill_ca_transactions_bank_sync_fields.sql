-- Le prime transazioni sincronizzate via Enable Banking sono state salvate con descrizione
-- vuota, importo sempre positivo e type='bank_sync' fisso, per un mapping campi errato
-- (corretto in enable-banking-sync). Ricalcola i valori corretti dal JSON grezzo già
-- conservato in ca_transactions.raw, senza perdita di dati e senza toccare le transazioni CSV.

UPDATE ca_transactions t
SET
  description = COALESCE(
    NULLIF(
      CASE WHEN jsonb_typeof(t.raw->'remittance_information') = 'array'
           THEN (SELECT string_agg(elem, ' ') FROM jsonb_array_elements_text(t.raw->'remittance_information') elem)
           ELSE NULL
      END,
      ''
    ),
    t.raw->'creditor'->>'name',
    t.raw->'debtor'->>'name',
    t.description
  ),
  amount = CASE
    WHEN t.raw->>'credit_debit_indicator' = 'CRDT' THEN abs(t.amount)
    WHEN t.raw->>'credit_debit_indicator' = 'DBIT' THEN -abs(t.amount)
    ELSE t.amount
  END,
  type = COALESCE(t.raw->'bank_transaction_code'->>'code', t.type)
WHERE t.import_source = 'bank_sync' AND t.raw IS NOT NULL;
