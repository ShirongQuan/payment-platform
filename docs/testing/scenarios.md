# Test Scenarios Catalogue

> Last updated: 2026-09-15
> Purpose: correctness checklist for payment-critical behaviour

## Table of Contents

- [1) How to use this catalogue](#1-how-to-use-this-catalogue)
- [2) Business-critical scenarios](#2-business-critical-scenarios)
    - [S1) Authorise success](#s1-authorise-success)
    - [S2) Insufficient funds decline](#s2-insufficient-funds-decline)
    - [S3) Duplicate request with same idempotency key and same payload](#s3-duplicate-request-with-same-idempotency-key-and-same-payload)
    - [S4) Duplicate idempotency key with different payload](#s4-duplicate-idempotency-key-with-different-payload)
    - [S5) Capture after authorisation](#s5-capture-after-authorisation)
    - [S6) Refund/reversal flow (MVP = reversal)](#s6-refundreversal-flow-mvp--reversal)
    - [S7) Timeout/retry and eventual consistency](#s7-timeoutretry-and-eventual-consistency)
- [3) Scenario-to-test-type matrix](#3-scenario-to-test-type-matrix)
- [4) Exit criteria for scenario coverage](#4-exit-criteria-for-scenario-coverage)

## 1) How to use this catalogue

- each scenario below is a required proof point in the **target coverage policy**, not automatically evidence of an existing passing test
- "expected DB changes" refer to `auth-service` tables unless noted; ledger projections are in `ledger-service`
- map each scenario to one or more test types: `unit`, `integration`, `e2e`; verify implementation and passing results separately against test classes, scripts and CI artefacts

## 2) Business-critical scenarios

### S1) Authorise success

- **Preconditions:** account exists, `ACTIVE`, currency matches, sufficient `available_balance`, fraud decision
  `APPROVE`
- **Input/API:** `POST /authorisations` with `accountId`, `amount`, `currency`, `idempotencyKey`
- **Expected DB changes:**
    - `account.available_balance` decreases by amount
    - `account.reserved_balance` increases by amount
    - new `authorisation` row with status `AUTHORISED`
    - new `authorisation_event` with `event_type=AUTHORISATION_AUTHORISED`
    - new `outbox_event` row in `NEW` (then eventually `PUBLISHED`)
- **Expected events:** Kafka event `AUTHORISATION_AUTHORISED` on `auth.events`; ledger writes projection rows and
  `processed_event`
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
- **Expected events:** `AUTHORISATION_DECLINED` published (ledger intentionally does not project declines into
  settlement entries)
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
- **Input/API:** send the same key and operation within the same account scope, but change a relevant payload field (for example, amount). A different account may represent a different idempotency scope and must not automatically be treated as a conflict.
- **Expected DB changes:**
    - no second mutation on `account`
    - no second business event accepted for the conflicting payload
- **Expected events:** none for rejected conflicting attempt
- **Expected API response/status:** `409 Conflict`, `errorCode=IDEMPOTENCY_CONFLICT`
- **Test types:** `unit`, `integration`, `e2e`

### S5) Capture after authorisation

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

### S6) Reversal of an uncaptured authorisation

- **Preconditions:** authorisation exists in `AUTHORISED` (not yet captured). The MVP releases an existing reservation; it does **not** implement a post-capture refund.
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

### S7) Fraud timeout and idempotent retry

- **Preconditions:** fraud-service slow/error mode; authorisation request uses a stable idempotency key
- **Input/API:** `POST /authorisations` during timeout window; optionally retry same request idempotently
- **Expected DB changes:**
    - auth result follows policy (`DECLINED` by default when fraud unavailable, unless fail-open allow-list applies)
    - exactly one committed business outcome for same idempotency key
    - the committed authorisation outcome and corresponding outbox row are not duplicated by a retry
- **Expected events:**
    - an authorisation outcome event is enqueued for publication according to the normal outbox policy; fraud timeouts do not themselves produce ledger DLT records
- **Expected API response/status:** deterministic status per policy (`200` decline/approve, or mapped errors for invalid
  requests)
- **Test types:** `integration`, `e2e`

### S8) Outbox publication delayed or exhausted

- **Preconditions:** PostgreSQL available; Kafka unavailable or publishing deliberately failing.
- **Input/API:** commit a payment operation while publication is disrupted.
- **Expected DB changes:** the domain mutation and outbox row commit atomically; no duplicate
  financial side effect occurs. Pending rows follow the configured backoff; after the configured
  five-attempt limit, a row may become terminally `FAILED`.
- **Expected events:** on recoverable failure, publication resumes and the ledger can catch up.
  Terminal `FAILED` rows require operator investigation and controlled requeue; recovery is not
  automatic merely because Kafka returns.
- **Expected API response/status:** the synchronous payment response follows the committed domain
  outcome, independently of subsequent Kafka publication.
- **Test types:** `integration`, `e2e` (fault-injection verification).

### S9) Ledger consumer failure and DLT routing

- **Preconditions:** a malformed/non-retryable event or a retry-exhausting consumer failure.
- **Input/API:** inject a controlled record into `auth.events` in an isolated test environment.
- **Expected DB changes:** no successful ledger projection for the failed event; a DLT publication
  is evidence of abandoned normal processing, not a completed business projection.
- **Expected events:** retryable errors receive the configured consumer retries (three retries
  after the initial attempt); non-retryable records go directly to `auth.events.ledger.dlt`.
- **Expected API response/status:** not applicable to the asynchronous consumer operation.
- **Test types:** `integration`, `e2e`.

## 3) Scenario-to-test-type matrix

The matrix below specifies **required coverage (target policy)**, not confirmed implemented
coverage. Record concrete test names and passing CI or execution evidence separately.

| Scenario                              | Unit                       | Integration | E2E      |
|---------------------------------------|----------------------------|-------------|----------|
| S1 Authorise success                  | Required                   | Required    | Required |
| S2 Insufficient funds                 | Required                   | Required    | Required |
| S3 Duplicate same payload             | Required                   | Required    | Required |
| S4 Duplicate different payload        | Required                   | Required    | Required |
| S5 Capture after authorisation        | Required                   | Required    | Required |
| S6 Reversal flow                      | Required                   | Required    | Required |
| S7 Fraud timeout/idempotent retry     | Optional helper unit tests | Required    | Required |
| S8 Outbox delayed/terminal failure    | Optional helper unit tests | Required    | Required |
| S9 Consumer retry/DLT                 | Optional helper unit tests | Required    | Required |

## 4) Exit criteria for scenario coverage

- target: every `Required` cell above has at least one automated test in the corresponding level; record test class/script and a passing result before marking it verified
- scenario assertions include API contract + state mutation + event/outbox verification
- failing scenario tests block merge only when the corresponding CI jobs and required branch-protection checks are enabled (see `docs/testing/strategy.md`)


