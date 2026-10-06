-- ══════════════════════════════════════════════════════════════════════════
-- 🛠️ Modifiche — una richiesta può toccare PIÙ APP (issue #23)
-- ══════════════════════════════════════════════════════════════════════════
--
-- `mod_richieste.app` è testo e ne ammette una sola. Parecchie richieste ne
-- toccano due — «la bolla in home e la stessa schermata in nativo» — e finora
-- toccava sceglierne una e scriverlo nella descrizione, cioè metterlo dove
-- nessun filtro lo legge.
--
-- ⚠️ **Una colonna nuova e non un elenco separato da virgole dentro `app`**:
-- quello sarebbe un formato da interpretare dentro un campo di testo, cioè il
-- caso `'uovo|uova'` che CLAUDE.md porta come esempio di quel che non si fa
-- (vedi la porzione abituale in `al_foods`). Un elenco è un array, e Postgres
-- ce l'ha.
--
-- ⚠️ **`app` RESTA e continua a portare la prima scelta.** Non è un doppione
-- lasciato per pigrizia: la legge la Edge Function `modifiche-issue` per
-- l'intestazione della issue, e le issue **già aperte** la citano. Toglierla
-- adesso vorrebbe dire cambiare insieme tabella, pagina ed Edge Function, e
-- scoprire il pezzo dimenticato da una issue che nasce senza nome dell'app.
-- La verità diventa `apps`; `app` è la sua prima voce, riscritta a ogni
-- salvataggio dalla pagina. Si toglierà con una migration sua, il giorno che
-- non la legga più nessuno.
--
-- ⚠️ **`text[]` e non una tabella di collegamento**: le app non sono righe di
-- nessuna tabella — `APP_SUGGERITE` è un elenco di suggerimenti dentro
-- `modifiche.html`, e il campo resta **testo libero** come `item_key` in
-- Finanza. Una tabella di collegamento avrebbe una chiave esterna verso
-- qualcosa che non esiste. È la stessa scelta di `cm_bank_connections.uses` e
-- di `ts_tasks.categories`.
--
-- ⚠️ **`NOT NULL DEFAULT '{}'`**: un elenco vuoto è uno stato buono — «non ho
-- ancora detto quale app tocca » — ed è quel che dicono già oggi le righe con
-- `app` NULL. NULL accanto a `'{}'` sarebbero due modi di dire la stessa cosa.

alter table mod_richieste
    add column if not exists apps text[] not null default '{}';

-- Le righe già in archivio: quella che hanno diventa la prima (e unica) voce.
-- Idempotente — tocca le sole righe ancora vuote, quindi rieseguirla non
-- riscrive un elenco corretto a mano nel frattempo.
update mod_richieste
   set apps = array[app]
 where apps = '{}'
   and app is not null
   and btrim(app) <> '';

-- Un indice GIN perché «tutte le richieste che toccano Finanza» sia una
-- domanda che si può fare: `apps @> array['Finanza']`. Oggi la pagina filtra
-- solo per stato, ma la colonna nasce per essere interrogata così.
create index if not exists mod_richieste_apps_idx
    on mod_richieste using gin (apps);

do $$
begin
    raise notice 'mod_richieste.apps: % righe hanno almeno un''app',
        (select count(*) from mod_richieste where cardinality(apps) > 0);
end $$;
