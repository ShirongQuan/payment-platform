DROP TABLE IF EXISTS fraud_evaluation;

CREATE TABLE fraud_evaluation (
  evaluation_id        UUID PRIMARY KEY,
  account_id           UUID NOT NULL,
  amount               decimal(19,2) not null default 0,
  currency_code        varchar(3) NOT NULL,
  merchant_reference   VARCHAR(128),
  idempotency_key      varchar(30) not null,
  request_hash         varchar(64) not null,
  risk_score           INT NOT NULL default 0,
  decision             VARCHAR(16) NOT NULL,
  rules_version        VARCHAR(16) NOT NULL,
  rule_result          JSONB NOT NULL DEFAULT '[]'::jsonb,
  ip_address           INET,
  correlation_id       UUID NOT NULL,
  created_at           timestamp with time zone not null,
  lock_recommended     BOOLEAN NOT NULL DEFAULT FALSE,
  lock_reason_code     VARCHAR(64),

  CONSTRAINT chk_risk_score_non_negative CHECK (risk_score >= 0),
  CONSTRAINT chk_decision_values CHECK (decision IN ('APPROVE', 'PENDING', 'DECLINE')),
  CONSTRAINT chk_currency_code_iso CHECK (
      currency_code = upper(currency_code) and char_length(currency_code) = 3
  )
);

CREATE UNIQUE INDEX uq_authorisation_event_account_idempotency
ON fraud_evaluation (account_id, idempotency_key);
