-- RPC per assegnare la categoria a una transazione Analisi Costi direttamente dalla schermata
-- di blocco Smart Block Android (categorizzazione interattiva delle transazioni Revolut residue).
-- SECURITY DEFINER: l'app Android chiama sempre con anon key (nessun JWT utente, stesso pattern
-- di task_complete/sf_checkin_set), quindi bypassa la RLS auth.uid()-based di ca_transaction_categories.
--
-- Replica esattamente la logica di setTxCategory + learnMerchantCategories in cost-analysis.html
-- (singola categoria per transazione, apprendimento merchant), MA senza la proposta di propagare
-- la correzione a tutto lo storico dello stesso merchant (previewMerchantCategoryPropagation):
-- quella richiede una conferma esplicita con anteprima, inadatta a una schermata di blocco.
create or replace function ca_smart_block_set_category(p_transaction_id uuid, p_category_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_tx ca_transactions;
  v_key text;
  v_merchant_map_id uuid;
begin
  select * into v_tx from ca_transactions where id = p_transaction_id;
  if v_tx is null then
    return jsonb_build_object('ok', false, 'error', 'transazione non trovata');
  end if;

  if not exists (select 1 from ca_categories where id = p_category_id and user_id = v_tx.user_id) then
    return jsonb_build_object('ok', false, 'error', 'categoria non valida');
  end if;

  delete from ca_transaction_categories where transaction_id = p_transaction_id;
  insert into ca_transaction_categories (transaction_id, category_id, source)
    values (p_transaction_id, p_category_id, 'manual');

  -- Apprendimento merchant (stessa normalizzazione di normalizeMerchant() in cost-analysis.html):
  -- le prossime transazioni dello stesso merchant verranno categorizzate automaticamente.
  v_key := btrim(
    regexp_replace(
      regexp_replace(upper(btrim(coalesce(v_tx.description, ''))), '[[:space:]]+', ' ', 'g'),
      '[*#]?[[:space:]]*[0-9]{3,}$', ''
    )
  );
  if v_key <> '' then
    select id into v_merchant_map_id from ca_merchant_map where user_id = v_tx.user_id and merchant_key = v_key;
    if v_merchant_map_id is null then
      insert into ca_merchant_map (user_id, merchant_key) values (v_tx.user_id, v_key) returning id into v_merchant_map_id;
    else
      update ca_merchant_map set updated_at = now() where id = v_merchant_map_id;
    end if;
    delete from ca_merchant_map_categories where merchant_map_id = v_merchant_map_id;
    insert into ca_merchant_map_categories (merchant_map_id, category_id) values (v_merchant_map_id, p_category_id);
  end if;

  return jsonb_build_object('ok', true);
end;
$$;

grant execute on function ca_smart_block_set_category(uuid, uuid) to anon, authenticated;
