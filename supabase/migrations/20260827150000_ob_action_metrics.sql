-- Obiettivi — un'azione può muovere PIÙ metriche
-- ===========================================================================
-- Un'azione e una metrica rispondono a due domande diverse dello stesso
-- obiettivo — «cosa faccio» e «come va» — e il collegamento dice quale delle
-- due cose la fatica dovrebbe spostare. Le metriche sono più d'una perché una
-- lezione di conversazione muove insieme la scioltezza e il numero di pause.
--
-- Tabella di collegamento e non una colonna `metric_ids uuid[]` su ob_actions
-- (com'è invece `categories`): lì l'array è obbligato, perché `cm_categories`
-- non sta in nessuna migration e una FK farebbe fallire il db push su un
-- database nuovo. `ob_metrics` invece c'è, quindi la chiave esterna si può
-- avere davvero — e fa un lavoro vero: cancellata una metrica, i suoi
-- collegamenti spariscono da sé invece di restare come id che puntano al nulla.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ob_action_metrics (
  action_id  uuid NOT NULL REFERENCES ob_actions(id) ON DELETE CASCADE,
  metric_id  uuid NOT NULL REFERENCES ob_metrics(id) ON DELETE CASCADE,
  user_id    uuid DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (action_id, metric_id)
);
CREATE INDEX IF NOT EXISTS idx_ob_action_metrics_metric ON ob_action_metrics(metric_id);
CREATE INDEX IF NOT EXISTS idx_ob_action_metrics_user   ON ob_action_metrics(user_id);
ALTER TABLE ob_action_metrics ENABLE ROW LEVEL SECURITY;
CREATE POLICY "ob_action_metrics_own" ON ob_action_metrics FOR ALL USING (user_id = auth.uid());

-- ---------------------------------------------------------------------------
-- Un'azione si collega alle metriche del PROPRIO obiettivo, e solo a quelle.
--
-- Il form offre già soltanto quelle, ma l'obiettivo di un'azione si può
-- cambiare, e in quel momento i collegamenti di prima parlano di metriche che
-- con l'obiettivo nuovo non c'entrano più. Il trigger li **toglie** invece di
-- rifiutare la modifica: spostare un'azione sotto un altro obiettivo è una
-- cosa legittima, ed è il collegamento a non avere più senso, non lo
-- spostamento. Senza, resterebbero lì a mostrare metriche di un obiettivo che
-- non si sta guardando.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION ob_action_metrics_pulisci()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = public
AS $$
BEGIN
  DELETE FROM ob_action_metrics am
   USING ob_metrics m
   WHERE am.action_id = NEW.id
     AND am.metric_id = m.id
     AND m.objective_id <> NEW.objective_id;
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_ob_action_metrics_pulisci ON ob_actions;
CREATE TRIGGER trg_ob_action_metrics_pulisci
  AFTER UPDATE OF objective_id ON ob_actions
  FOR EACH ROW
  WHEN (OLD.objective_id IS DISTINCT FROM NEW.objective_id)
  EXECUTE FUNCTION ob_action_metrics_pulisci();
