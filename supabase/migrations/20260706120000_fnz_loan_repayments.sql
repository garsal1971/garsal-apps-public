-- Rimborsi parziali sui prestiti (fnz_loans, type = PRESTITO)
--
-- Ogni rimborso riduce il saldo residuo del prestito da quella data in poi;
-- gli interessi successivi vengono ricalcolati dal nuovo saldo (logica in
-- finanza.html / situazione-rosa.html, non lato DB).

CREATE TABLE IF NOT EXISTS fnz_loan_repayments (
  id         uuid           PRIMARY KEY DEFAULT gen_random_uuid(),
  loan_id    uuid           NOT NULL REFERENCES fnz_loans(id) ON DELETE CASCADE,
  user_id    uuid           NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  date       date           NOT NULL,
  amount     numeric(18, 2) NOT NULL CHECK (amount > 0),
  notes      text           NOT NULL DEFAULT '',
  created_at timestamptz    NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS fnz_loan_repayments_loan_idx ON fnz_loan_repayments(loan_id);
CREATE INDEX IF NOT EXISTS fnz_loan_repayments_user_idx ON fnz_loan_repayments(user_id);

ALTER TABLE fnz_loan_repayments ENABLE ROW LEVEL SECURITY;
CREATE POLICY "fnz_loan_repayments_own" ON fnz_loan_repayments FOR ALL USING (user_id = auth.uid());

-- Accesso ospite in sola lettura, coerente con guest_read_loans
-- (situazione-rosa.html mostra "Per via galli" e i prestiti "Da Rosa")
DROP POLICY IF EXISTS "guest_read_loan_repayments" ON fnz_loan_repayments;
CREATE POLICY "guest_read_loan_repayments" ON fnz_loan_repayments
  FOR SELECT
  USING (
    public.has_page_access('situazione-rosa.html')
    AND EXISTS (
      SELECT 1 FROM fnz_loans l
      WHERE l.id = fnz_loan_repayments.loan_id
        AND (l.name ILIKE '%via galli%' OR l.name ILIKE '%da rosa%')
    )
  );
