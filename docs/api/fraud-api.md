# Fraud Service API

Base path: `/` (server port `9010`)

This document covers the important fraud-service endpoint and the expected behavior for:

- request
- response
- decision logic
- error cases
- idempotency behavior

Fraud-service is called **synchronously over REST** by auth-service as a pre-check before an
authorisation reserves funds (see [Auth API](./auth-api.md#post-authorisations)). It is not driven by
Kafka events.

## Shared Error Format

Domain errors are returned as RFC 7807 `application/problem+json` from `FraudExceptionHandler`.

Typical fields:

- `title`
- `status`
- `detail`
- `instance`
- `errorCode`

---

## POST `/fraud/check`

Evaluates fraud/risk for a prospective authorisation and returns an APPROVE/DECLINE decision.

### Request

```json
{
  "accountId": "uuid",
  "idempotencyKey": "auth-001",
  "amount": 10.00,
  "currencyCode": "GBP",
  "merchantReference": "order-123",
  "ipAddress": "203.0.113.5"
}
```

Constraints:

- `accountId`: required UUID
- `idempotencyKey`: required, non-blank (paired with `accountId` to uniquely identify one evaluation
  attempt — typically the same idempotency key the caller used for the authorisation)
- `amount`: required, minimum `0.01`
- `currencyCode`: required, non-blank, valid ISO currency code
- `merchantReference`: required, non-blank, max 128 chars
- `ipAddress`: required, non-blank (used by the IP velocity rule)

### Response

HTTP `200 OK`

```json
{
  "decision": "APPROVE",
  "riskScore": 25,
  "reasons": [
    {
      "ruleName": "amount-deviation",
      "score": 25,
      "reason": "AMOUNT_DEVIATION"
    }
  ],
  "lockAccountRecommended": false,
  "lockReasonCode": null
}
```

`decision` is one of `APPROVE` or `DECLINE` (an internal `PENDING` state exists only while an
evaluation is being scored; it is never returned to the caller — a concurrent duplicate request
observes it as `409 FRAUD_EVALUATION_IN_PROGRESS`, see below).

`reasons` lists every rule that contributed a non-zero score (`ruleName`, `score`, `reason` code); an
`APPROVE` with no triggered rules returns an empty list.

`lockAccountRecommended` / `lockReasonCode` are only ever set (and only ever `true`) on a `DECLINE`
decision, and only when the account-lock rule is enabled. It signals that auth-service should lock the
account; it is `false`/`null` for every `APPROVE`.

### Decision logic

1. A `PENDING` evaluation row is inserted keyed by `(accountId, idempotencyKey)` (unique constraint) so
   concurrent duplicate requests for the same key can be detected.
2. Configured risk rules run and contribute a score (see `risk.rules` in `application.yml`):
    - **IP velocity**: score `40` if more than `3` requests from the same account within `5s` from the
      given `ipAddress` (reason `IP_VELOCITY_EXCEEDED`).
    - **Account velocity**: score `30` if more than `3` requests for the same account within `5s`
      (reason `ACCOUNT_VELOCITY_EXCEEDED`).
    - **Amount deviation**: score `25` if the request amount exceeds `3x` a rolling reference/average
      over the trailing `30` days (min `5` samples), cached for `30` minutes (reason
      `AMOUNT_DEVIATION`).
3. Decision: `DECLINE` if the summed `riskScore` is `>= risk.decline-threshold` (`70` by default),
   otherwise `APPROVE`.
4. Account-lock recommendation (only evaluated on `DECLINE`, when `risk.lock.enabled` is `true`):
    - Immediate: `riskScore >= risk.lock.high-risk-score-threshold` (`90`) →
      reason `FRAUD_HIGH_RISK_SCORE`.
    - Pattern-based: more than `risk.lock.repeated-decline-threshold` (`3`) declines for the same
      account within `risk.lock.repeated-decline-window-seconds` (`600`s / 10 min) →
      reason `REPEATED_DECLINE_PATTERN`.
5. The evaluation row is finalized (`PENDING` → `APPROVE`/`DECLINE`) so a retried request with the same
   `(accountId, idempotencyKey)` replays the stored decision instead of re-scoring.

### Error cases

- `400 Bad Request`: request validation failure (missing/invalid fields)
- `409 Conflict`: same `(accountId, idempotencyKey)` retried with a different `amount`, `currencyCode`,
  `merchantReference`, or `ipAddress` (`IDEMPOTENCY_CONFLICT`)
- `409 Conflict`: a duplicate request for the same `(accountId, idempotencyKey)` arrived while the
  original evaluation is still being scored (`FRAUD_EVALUATION_IN_PROGRESS`)

### Idempotency behavior

- Scoped by `(accountId, idempotencyKey)`, enforced via a database unique constraint (conditional
  insert of the `PENDING` row).
- If the same key is retried with an identical payload after the original evaluation finished, the
  previously finalized decision is replayed (no re-scoring).
- If the same key is retried with a different payload, returns `409 IDEMPOTENCY_CONFLICT`.
- If the same key is retried while the original evaluation is still `PENDING`, returns
  `409 FRAUD_EVALUATION_IN_PROGRESS`.

## Caller-side resilience (auth-service)

Not part of fraud-service itself, but relevant to how the endpoint is consumed:

- auth-service wraps calls to this endpoint with a resilience4j circuit breaker (10-call sliding
  window, opens at ≥50% failure/slow-call rate, calls >100ms count as slow) and a 250ms time limiter.
- HTTP client timeouts: 500ms connect / 1000ms read.
- If the call fails, times out, or the circuit is open, auth-service treats the authorisation as
  declined (`FRAUD_SERVICE_UNAVAILABLE`, risk score `100`) unless an (off-by-default) fail-open policy
  allows a small allow-list of trusted accounts at/below a configured amount ceiling to proceed as
  approved instead.

