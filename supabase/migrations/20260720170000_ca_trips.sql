-- Viaggi (periodi fuori Bologna): usati per attribuire in automatico la categoria "Vacanza"
-- alle transazioni con carta (type = 'CARD_PAYMENT') la cui data ricade nel periodo.

CREATE TABLE IF NOT EXISTS ca_trips (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid REFERENCES auth.users(id) ON DELETE CASCADE,
  name text NOT NULL,
  start_date date NOT NULL,
  end_date date NOT NULL,
  created_at timestamptz DEFAULT now()
);

ALTER TABLE ca_trips ENABLE ROW LEVEL SECURITY;

CREATE POLICY ca_trips_owner ON ca_trips FOR ALL USING (user_id = auth.uid());

CREATE INDEX IF NOT EXISTS idx_ca_trips_user ON ca_trips(user_id);
