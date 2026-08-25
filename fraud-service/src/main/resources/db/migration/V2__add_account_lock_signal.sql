-- Adds the account-lock recommendation signal returned alongside a fraud decision.
ALTER TABLE fraud_evaluation
  ADD COLUMN IF NOT EXISTS lock_recommended BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS lock_reason_code VARCHAR(64);

