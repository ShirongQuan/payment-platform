
CREATE TABLE IF NOT EXISTS fraud_evaluation (
  evaluation_id        UUID PRIMARY KEY,
  account_id           UUID NOT NULL,
  amount               decimal(19,2) not null default 0,
  currency_code        varchar(3) NOT NULL,
  merchant_reference   VARCHAR(128),
  idempotency_key      varchar(30) not null,
  risk_score           INT NOT NULL default 0,
  decision             VARCHAR(16) NOT NULL,              -- APPROVE / DECLINE
  rules_version        VARCHAR(16) NOT NULL,
  rule_result          JSONB NOT NULL DEFAULT '[]'::jsonb, -- array of reason objects/codes
  ip_address           INET,
  request_hash         varchar(64) NOT NULL, -- sha256 hex
  correlation_id       UUID NOT NULL,
  created_at           timestamp with time zone not null,

  CONSTRAINT chk_risk_score_non_negative CHECK (risk_score >= 0),
  CONSTRAINT chk_decision_values CHECK (decision IN ('APPROVE', 'PENDING', 'DECLINE')),
    constraint chk_currency_code_iso check (
        currency_code = upper(currency_code) and char_length(currency_code) = 3
    )
);

-- Enforces idempotency per account
create unique index if not exists uq_authorisation_event_account_idempotency
    on fraud_evaluation (account_id, idempotency_key);

-- Partial index to speed up amount-deviation rule query
CREATE INDEX ix_fraud_eval_acc_created_approve
ON fraud_evaluation (account_id, created_at DESC)
INCLUDE (amount)
WHERE decision = 'APPROVE';

-- Hot-path lookup for account recent history (amount baseline, prior declines, etc.)
CREATE INDEX IF NOT EXISTS ix_fraud_eval_account_created_at
  ON fraud_evaluation (account_id, created_at DESC);
