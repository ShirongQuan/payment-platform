# Test Scenarios Catalog

> Last updated: 2026-09-15
> Purpose: correctness checklist for payment-critical behavior

## Table of Contents

- [1) How to use this catalog](#1-how-to-use-this-catalog)
- [2) Business-critical scenarios](#2-business-critical-scenarios)
    - [S1) Authorize success](#s1-authorize-success)
    - [S2) Insufficient funds decline](#s2-insufficient-funds-decline)
    - [S3) Duplicate request with same idempotency key and same payload](#s3-duplicate-request-with-same-idempotency-key-and-same-payload)
    - [S4) Duplicate idempotency key with different payload](#s4-duplicate-idempotency-key-with-different-payload)
    - [S5) Capture after authorization](#s5-capture-after-authorization)
    - [S6) Refund/reversal flow (MVP = reversal)](#s6-refundreversal-flow-mvp--reversal)
    - [S7) Timeout/retry and eventual consistency](#s7-timeoutretry-and-eventual-consistency)
- [3) Scenario-to-test-type matrix](#3-scenario-to-test-type-matrix)
- [4) Exit criteria for scenario coverage](#4-exit-criteria-for-scenario-coverage)

## 1) How to use this catalog

- each scenario below is a required proof point, not just an example
- "expected DB changes" refer to `auth-service` tables unless noted; ledger projections are in `ledger-service`
- map each scenario to one or more test types: `unit`, `integration`, `e2e`

## 2) Business-critical scenarios

### S1) Authorize success

- **Preconditions:** account exists, `ACTIVE`, currency matches, sufficient `available_balance`, fraud decision `APPROVE`
- **Input/API:** `POST /authorisations` with `accountId`, `amount`, `currency`, `idempotencyKey`
- **Expected DB changes:**
  - `account.available_balance` decreases by amount
  - `account.reserved_balance` increases by amount
  - new `authorisation` row with status `AUTHORISED`
  - new `authorisation_event` with `event_type=AUTHORISATION_AUTHORISED`
  - new `outbox_event` row in `NEW` (then eventually `PUBLISHED`)
- **Expected events:** Kafka event `AUTHORISATION_AUTHORISED` on `auth.events`; ledger writes projection rows and `processed_event`
- **Expected API response/status:** `200 OK`, body includes `status=AUTHORISED`
- **Test types:** `unit`, `integration`, `e2e`

### S2) Insufficient funds decline

- **Preconditions:** account exists, `ACTIVE`, requested amount > `available_balance`
- **Input/API:** `POST /authorisations`
- **Expected DB changes:**
  - no balance movement on `account`
  - `authorisation` persisted as `DECLINED`
  - `authorisation_event` persisted as `AUTHORISATION_DECLINED` with reason `INSUFFICIENT_FUNDS`
  - `outbox_event` exists for decline audit/eventing path
- **Expected events:** `AUTHORISATION_DECLINED` published (ledger intentionally does not project declines into settlement entries)
- **Expected API response/status:** `200 OK`, body includes `status=DECLINED`, reason indicates insufficient funds
- **Test types:** `unit`, `integration`, `e2e`

### S3) Duplicate request with same idempotency key and same payload

- **Preconditions:** first request already committed for same `(accountId, idempotencyKey, eventType)`
- **Input/API:** re-send the exact same `POST /authorisations` payload with same `idempotencyKey`
- **Expected DB changes:**
  - no additional balance movement
  - no duplicate `authorisation_event` business effect (unique constraint protects source of truth)
  - Redis idempotency cache may serve replay response (no new business mutation)
- **Expected events:** no new business event for replay call
- **Expected API response/status:** `200 OK`, response matches first request semantically
- **Test types:** `unit`, `integration`, `e2e`

### S4) Duplicate idempotency key with different payload

- **Preconditions:** first request committed for `(accountId, idempotencyKey)`
- **Input/API:** send same key but different amount/currency/account payload
- **Expected DB changes:**
  - no second mutation on `account`
  - no second business event accepted for the conflicting payload
- **Expected events:** none for rejected conflicting attempt
- **Expected API response/status:** `409 Conflict`, `errorCode=IDEMPOTENCY_CONFLICT`
- **Test types:** `unit`, `integration`, `e2e`

### S5) Capture after authorization

- **Preconditions:** authorisation exists in `AUTHORISED`
- **Input/API:** `POST /authorisations/{authorisationId}/captures` with capture `idempotencyKey`
- **Expected DB changes:**
  - `authorisation.status` transitions `AUTHORISED -> CAPTURED`
  - `account.reserved_balance` decreases by amount
  - `account.available_balance` unchanged at capture time (already reduced during reserve)
  - `authorisation_event` with `event_type=AUTHORISATION_CAPTURED`
  - `outbox_event` created and eventually published
- **Expected events:** Kafka `AUTHORISATION_CAPTURED`; ledger projection includes capture entry and `processed_event`
- **Expected API response/status:** `200 OK`, body includes `status=CAPTURED`
- **Test types:** `unit`, `integration`, `e2e`

### S6) Refund/reversal flow (MVP = reversal)

- **Preconditions:** authorisation exists in `AUTHORISED`
- **Input/API:** `POST /authorisations/{authorisationId}/reversals` with reversal `idempotencyKey`
- **Expected DB changes:**
  - `authorisation.status` transitions `AUTHORISED -> REVERSED`
  - `account.reserved_balance` decreases by amount
  - `account.available_balance` increases by amount (reservation released)
  - `authorisation_event` with `event_type=AUTHORISATION_REVERSED` and reason (`CUSTOMER_REQUEST`/`MERCHANT_REQUEST`)
  - `outbox_event` created and eventually published
- **Expected events:** Kafka `AUTHORISATION_REVERSED`; ledger projection includes reversal entry and `processed_event`
- **Expected API response/status:** `200 OK`, body includes `status=REVERSED`
- **Test types:** `unit`, `integration`, `e2e`

### S7) Timeout/retry and eventual consistency

- **Preconditions:** fraud-service slow/error mode or temporary dependency issue; outbox scheduler active
- **Input/API:** `POST /authorisations` during timeout window; optionally retry same request idempotently
- **Expected DB changes:**
  - auth result follows policy (`DECLINED` by default when fraud unavailable, unless fail-open allow-list applies)
  - exactly one committed business outcome for same idempotency key
  - outbox rows retry until publish succeeds or failure path is visible
  - downstream ledger eventually converges once event is consumed (or failure appears in DLT path)
- **Expected events:**
  - normal event on success path, or
  - DLT event evidence when consumer retries are exhausted for malformed/non-retryable records
- **Expected API response/status:** deterministic status per policy (`200` decline/approve, or mapped errors for invalid requests)
- **Test types:** `integration`, `e2e`

## 3) Scenario-to-test-type matrix

| Scenario | Unit | Integration | E2E |
|---|---|---|---|
| S1 Authorize success | Required | Required | Required |
| S2 Insufficient funds | Required | Required | Required |
| S3 Duplicate same payload | Required | Required | Required |
| S4 Duplicate different payload | Required | Required | Required |
| S5 Capture after authorization | Required | Required | Required |
| S6 Reversal flow | Required | Required | Required |
| S7 Timeout/retry/eventual consistency | Optional helper unit tests | Required | Required |

## 4) Exit criteria for scenario coverage

- every `Required` cell above has at least one automated test in the corresponding level
- scenario assertions include API contract + state mutation + event/outbox verification
- failing scenario tests block merge once CI gates are enabled (see `docs/testing/strategy.md`)


