-- Super-categorie di Spese Ada: un secondo livello sopra le voci di spesa, ed è l'unico che
-- porta il colore — le voci lo ereditano dalla propria super-categoria.
--
-- Tabella separata e non un parent_id dentro ada_categories: con l'autoreferenza «super» e
-- «voce» si distinguerebbero solo dal fatto che parent_id è nullo, e una voce senza super
-- diventerebbe indistinguibile da una super-categoria. Le voci senza super devono poter
-- esistere (sono le sei di partenza), quindi i due livelli sono due cose diverse davvero.

CREATE TABLE IF NOT EXISTS ada_super_categories (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id    uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  name       text NOT NULL,
  icon       text,
  color      text,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_ada_super_categories_user ON ada_super_categories(user_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_ada_super_categories_user_name
  ON ada_super_categories(user_id, lower(name));

ALTER TABLE ada_super_categories ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS ada_super_categories_own ON ada_super_categories;
CREATE POLICY ada_super_categories_own ON ada_super_categories FOR ALL
  USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());

-- SET NULL e non CASCADE: cancellare una super-categoria non deve portarsi via le voci (e con
-- loro la categoria dei movimenti). Le voci tornano semplicemente «senza super-categoria».
ALTER TABLE ada_categories
  ADD COLUMN IF NOT EXISTS super_id uuid REFERENCES ada_super_categories(id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS idx_ada_categories_super ON ada_categories(super_id);

-- Le sei categorie create dalla migration precedente restano voci di dettaglio senza super:
-- nessun dato viene toccato qui, le super-categorie se le crea l'utente.

COMMENT ON COLUMN ada_categories.super_id IS
  'Super-categoria di appartenenza. NULL = voce senza super, mostrata a parte nei riepiloghi.';
COMMENT ON COLUMN ada_categories.color IS
  'Non più usato dall''app: il colore vive sulla super-categoria e la voce lo eredita. Colonna lasciata per non perdere i valori esistenti.';
