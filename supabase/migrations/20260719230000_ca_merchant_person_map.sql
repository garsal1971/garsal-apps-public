-- Apprendimento merchant -> persona per "per chi è la spesa" (person_id), analogo a
-- ca_merchant_map/ca_merchant_map_categories già usato per le categorie. Quando l'utente
-- assegna manualmente "per chi" a una transazione (senza usare l'opzione "solo questa
-- transazione"), la scelta viene ricordata qui e riapplicata automaticamente alle transazioni
-- future/esistenti dello stesso merchant, con priorità sulle regole parola-chiave persona.

CREATE TABLE IF NOT EXISTS ca_merchant_person_map (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid REFERENCES auth.users(id) ON DELETE CASCADE,
  merchant_key text NOT NULL,
  person_id uuid REFERENCES ca_people(id) ON DELETE CASCADE,
  updated_at timestamptz DEFAULT now(),
  UNIQUE(user_id, merchant_key)
);

ALTER TABLE ca_merchant_person_map ENABLE ROW LEVEL SECURITY;

CREATE POLICY ca_merchant_person_map_owner ON ca_merchant_person_map FOR ALL USING (user_id = auth.uid());

CREATE INDEX IF NOT EXISTS idx_ca_merchant_person_map_user_key ON ca_merchant_person_map(user_id, merchant_key);
