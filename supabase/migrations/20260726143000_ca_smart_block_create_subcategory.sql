-- RPC per creare al volo una sotto-categoria direttamente dalla schermata di blocco Smart Block
-- Android (picker categorie Analisi Costi), quando nessuna categoria esistente va bene per la
-- transazione mostrata. Stesso pattern di ca_smart_block_set_category (migration 20260724190000):
-- SECURITY DEFINER perché l'app Android chiama sempre con anon key (nessun JWT utente), user_id
-- derivato server-side dalla transazione (mai passato dal client) per evitare che l'anon key possa
-- scrivere categorie per un utente arbitrario.
create or replace function ca_smart_block_create_subcategory(p_transaction_id uuid, p_parent_id uuid, p_name text)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_tx      ca_transactions;
  v_parent  ca_categories;
  v_name    text := btrim(coalesce(p_name, ''));
  v_new_id  uuid;
begin
  select * into v_tx from ca_transactions where id = p_transaction_id;
  if v_tx is null then
    return jsonb_build_object('ok', false, 'error', 'transazione non trovata');
  end if;

  if v_name = '' then
    return jsonb_build_object('ok', false, 'error', 'nome mancante');
  end if;

  select * into v_parent from ca_categories where id = p_parent_id and user_id = v_tx.user_id;
  if v_parent is null then
    return jsonb_build_object('ok', false, 'error', 'categoria principale non valida');
  end if;
  if v_parent.parent_id is not null then
    return jsonb_build_object('ok', false, 'error', 'la categoria scelta non è una principale');
  end if;

  insert into ca_categories (user_id, parent_id, name, icon, color, excluded_from_totals, visible_in_app)
    values (v_tx.user_id, p_parent_id, v_name, null, null, false, true)
    returning id into v_new_id;

  return jsonb_build_object('ok', true, 'id', v_new_id);
end;
$$;

grant execute on function ca_smart_block_create_subcategory(uuid, uuid, text) to anon, authenticated;
