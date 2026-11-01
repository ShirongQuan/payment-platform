-- V1 created the idempotency unique index with a copy-paste name from auth-service's
-- authorisation_event table. Renaming it here to correctly reflect that it belongs
-- to fraud_evaluation, without changing its definition/behaviour.
ALTER INDEX IF EXISTS uq_authorisation_event_account_idempotency
    RENAME TO uq_fraud_evaluation_account_idempotency;

