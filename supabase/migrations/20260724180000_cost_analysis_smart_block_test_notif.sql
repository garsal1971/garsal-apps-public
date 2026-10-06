-- Test one-tantum: inserisce una riga di prova in cm_notification_queue per verificare che
-- la notifica Smart Block "cost_analysis" arrivi davvero sul telefono (SupabaseApi.queryQueue
-- la mostra solo se metadata.device_token combacia col device — vedi fix in
-- revolut-auto-categorize v1.3). Nessun impatto sui dati reali di Analisi Costi: riusa la riga
-- "ancora" di cm_notification_rules creata da 20260724120000_cost_analysis_smart_block_notif.sql
-- solo per leggere rule_id/user_id, non la modifica.
insert into cm_notification_queue (rule_id, user_id, app, entity_id, title, body, channel, fire_at, status, metadata)
select r.id, r.user_id, 'cost_analysis', r.id,
       'TEST — notifica Smart Block Analisi Costi',
       'Questo è un test manuale, puoi ignorarlo e chiuderlo.',
       'smart_block', now(), 'pending',
       case when s.smart_block_device_token is not null
            then jsonb_build_object('device_token', s.smart_block_device_token)
            else null end
from cm_notification_rules r
left join cm_user_notification_settings s on s.user_id = r.user_id
where r.app = 'cost_analysis' and r.channel = 'smart_block'
limit 1;
