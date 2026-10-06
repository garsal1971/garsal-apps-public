-- Associa una carta (issuer + ultime cifre, es. MASTERCARD/7343) a una persona, per
-- attribuire automaticamente "chi ha speso" alle transazioni sincronizzate via Enable Banking
-- in base alla carta usata (debtor_account_additional_identification nel JSON grezzo).
-- Le carte non ancora assegnate (o le transazioni senza carta, es. bonifici/ricariche)
-- vengono attribuite alla persona "NUCLEO" (creata automaticamente al primo sync).

CREATE TABLE IF NOT EXISTS ca_card_person_map (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid REFERENCES auth.users(id) ON DELETE CASCADE,
  card_issuer text NOT NULL,
  card_identification text NOT NULL,
  person_id uuid REFERENCES ca_people(id) ON DELETE CASCADE,
  created_at timestamptz DEFAULT now(),
  UNIQUE(user_id, card_issuer, card_identification)
);

ALTER TABLE ca_card_person_map ENABLE ROW LEVEL SECURITY;

CREATE POLICY ca_card_person_map_owner ON ca_card_person_map FOR ALL USING (user_id = auth.uid());

CREATE INDEX IF NOT EXISTS idx_ca_card_person_map_user ON ca_card_person_map(user_id);
