-- ════════════════════════════════════════════════════════════════════════════
-- Piante — i GRUPPI (Balcone, Soggiorno, Succulente…)
-- ════════════════════════════════════════════════════════════════════════════
-- • pv_groups: i gruppi, con nome ed emoji.
-- • pv_plant_groups: quali piante stanno in quale gruppo. Una pianta può stare
--   in PIÙ gruppi (Balcone e Succulente insieme), quindi tabella di
--   collegamento e non una colonna. Cancellando un gruppo o una pianta il
--   collegamento sparisce da sé; cancellare un gruppo NON cancella le piante.
-- • pv_actions.group_id: un'azione è di UNA pianta OPPURE di UN gruppo, mai
--   tutte e due. L'azione di gruppo è UNA sola: un promemoria e un ✅ Fatto
--   valgono per tutte le piante del gruppo, anche quelle aggiunte dopo.
--   Cancellando il gruppo se ne vanno le sue azioni (il trigger
--   trg_pv_actions_togli_promemoria porta via i promemoria), come per la pianta.
-- ⚠️ Le RPC pv_action_* non cambiano: scrivono pv_action_history.plant_id dalla
--   riga, che per un'azione di gruppo è NULL — la colonna lo ammette già, e lo
--   storico si ritrova da action_id.

CREATE TABLE IF NOT EXISTS pv_groups (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     uuid NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
  name        text NOT NULL,
  emoji       text,
  created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS pv_groups_user_idx ON pv_groups (user_id, name);
ALTER TABLE pv_groups ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS pv_groups_owner ON pv_groups;
CREATE POLICY pv_groups_owner ON pv_groups
  FOR ALL USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());

CREATE TABLE IF NOT EXISTS pv_plant_groups (
  plant_id    uuid NOT NULL REFERENCES pv_plants(id) ON DELETE CASCADE,
  group_id    uuid NOT NULL REFERENCES pv_groups(id) ON DELETE CASCADE,
  user_id     uuid NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
  created_at  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (plant_id, group_id)
);
CREATE INDEX IF NOT EXISTS pv_plant_groups_group_idx ON pv_plant_groups (group_id);
ALTER TABLE pv_plant_groups ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS pv_plant_groups_owner ON pv_plant_groups;
-- ⚠️ Non basta user_id: senza i due EXISTS si potrebbe collegare la propria
-- riga alla pianta o al gruppo di un altro conoscendone l'id.
CREATE POLICY pv_plant_groups_owner ON pv_plant_groups
  FOR ALL USING (user_id = auth.uid())
  WITH CHECK (
    user_id = auth.uid()
    AND EXISTS (SELECT 1 FROM pv_plants p WHERE p.id = plant_id AND p.user_id = auth.uid())
    AND EXISTS (SELECT 1 FROM pv_groups g WHERE g.id = group_id AND g.user_id = auth.uid())
  );

ALTER TABLE pv_actions ADD COLUMN IF NOT EXISTS group_id uuid REFERENCES pv_groups(id) ON DELETE CASCADE;
ALTER TABLE pv_actions ALTER COLUMN plant_id DROP NOT NULL;
DO $$ BEGIN
  ALTER TABLE pv_actions ADD CONSTRAINT pv_actions_pianta_o_gruppo
    CHECK ((plant_id IS NULL) <> (group_id IS NULL));
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
CREATE INDEX IF NOT EXISTS pv_actions_group_idx ON pv_actions (group_id) WHERE group_id IS NOT NULL;
