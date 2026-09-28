# Payment Lifecycle

## Table of Contents

- [Purpose](#purpose)
- [Scope](#scope)
- [Lifecycle at a glance](#lifecycle-at-a-glance)
- [Sequence diagrams](#sequence-diagrams)
- [1) Authorisation flow](#1-authorisation-flow)
- [2) Capture flow](#2-capture-flow)
- [3) Reverse flow](#3-reverse-flow)
- [4) Ledger projection (consume side)](#4-ledger-projection-consume-side)
- [Step-to-data/event mapping](#step-to-dataevent-mapping)
- [Idempotency expectations (MVP)](#idempotency-expectations-mvp)
- [Observability hooks](#observability-hooks)
- [MVP notes for reviewers](#mvp-notes-for-reviewers)

## Purpose

Describe the canonical payment flow for this MVP platform and map each step to state transitions, persistence, and
emitted events.

## Scope

- Auth-service request handling and state transitions
- The synchronous fraud pre-check (fraud-service) and its account-lock side effect
- Ledger-side effects via published/consumed events
- Idempotency behavior for key mutating operations

See [Payment Platform MVP Progress](../roadmap.md) for what's planned beyond this MVP scope (e.g. refunds,
authentication/authorization, account-unlock flow).

---

## Lifecycle at a glance

Source: [Payment Lifecycle State / Account Lock State](./README.md#payment-lifecycle-state--account-lock-state)

This matches `AuthorisationStatus` exactly (`auth-service`): `AUTHORISED`, `CAPTURED`, `REVERSED`,
`DECLINED`. Once `CAPTURED`, the authorisation is terminal. `DECLINED` is a terminal state reached
directly from `[*]` — an authorisation is created already `DECLINED`, not transitioned into it
later.

A second, account-level state machine runs alongside this one:

Source: [Account Lock State](./README.md#payment-lifecycle-state--account-lock-state)

`AccountStatus` is `ACTIVE`/`LOCKED`. A `LOCKED` account rejects new authorisations
(`reason=ACCOUNT_LOCKED`) before fraud-service is even called. Unlocking an account back to
`ACTIVE` is a manual, operator-driven action today; an automated/API-driven unlock flow is tracked
on the roadmap.

---

## Sequence diagrams

See the [Diagram Index](./README.md#diagram-index) for the embedded diagrams and what each one
covers:

- Simplified happy-path overview (used in the root README): `payment-lifecycle-happy-path.mmd`
- Authorise (incl. fraud pre-check + account-lock gate): [Authorise Sequence](./README.md#authorise-sequence)
- Capture: [Capture Sequence](./README.md#capture-sequence)
- Reverse: [Reverse Sequence](./README.md#reverse-sequence)
- Generic idempotency replay/conflict rules: [Idempotency Generic Sequence](./README.md#idempotency-generic-sequence)
- Event publishing (auth-service outbox → Kafka): [Event Publishing Sequence](./README.md#event-publishing-sequence)
- Event consuming (ledger-service projection): [Event Consuming Sequence](./README.md#event-consuming-sequence)
- Outbox backlog detection/triage/recovery: [Outbox Backlog Recovery Flow](./README.md#outbox-backlog-recovery-flow)

---

## 1) Authorisation flow

**Reference diagram:** [Authorise Sequence](./README.md#authorise-sequence)

### Preconditions

- Required request fields are present and valid (`accountId`, `idempotencyKey`, `amount`,
  `currencyCode`, `merchantReference`).
- `idempotencyKey` is required and scoped to `(accountId, idempotencyKey)`.

### Expected behavior

1. Redis idempotency-cache check for `(AUTHORISE, accountId, idempotencyKey)`; a fingerprint hit
   replays the cached response immediately (no DB write, no fraud-service call).
2. On a cache miss, a best-effort DB idempotency pre-check runs (avoids paying for a fraud-service
   call on an already-processed, cache-cold retry); a matching row replays, a mismatched payload
   returns `409 IDEMPOTENCY_CONFLICT`.
3. If the account is not `ACTIVE` (`LOCKED`/inactive), fraud-service is **not** called at all — the
   request is declined immediately (`ACCOUNT_LOCKED`/`ACCOUNT_INACTIVE`).
4. Otherwise, auth-service calls fraud-service's `POST /fraud/check` synchronously (circuit
   breaker + 250ms time limiter). An `APPROVE` proceeds to reserve funds; a `DECLINE` (including
   fraud-service being unavailable, unless a fail-open policy applies) short-circuits to `DECLINED`
   without touching the account balance.
5. If fraud approved: reserve the requested amount (`availableBalance -= amount`,
   `reservedBalance += amount`). If available balance is insufficient, the authorisation is still
   persisted, as `DECLINED` with `reason=INSUFFICIENT_FUNDS` — the account itself is left
   untouched.
6. If fraud-service recommended locking the account (very high risk score, or a repeated-decline
   pattern within a trailing window) and the account is currently `ACTIVE`, the account is flipped
   to `LOCKED` in the same transaction as the `DECLINED` authorisation.
7. Persist the authorisation + authorisation event + outbox event in the same transactional
   boundary, then cache the response in Redis for future replay.

### Outputs

- Authorisation state is one of `AUTHORISED` (funds reserved) or `DECLINED` (insufficient funds,
  fraud decline, fraud-service unavailable, or account not active).
- On `AUTHORISED`: balance reservation is recorded (`reservedBalance` increases,
  `availableBalance` decreases by the same amount).
- On a fraud-driven `DECLINED` with a lock recommendation: the account additionally transitions
  `ACTIVE -> LOCKED`.
- Outbox entry created (`AUTHORISATION_AUTHORISED` or `AUTHORISATION_DECLINED`) for downstream
  propagation to ledger-service. The account-lock side effect itself is captured via the
  `DECLINED` authorisation event; a dedicated lock-transition event is a future enhancement.

---

## 2) Capture flow

**Reference diagram:** [Capture Sequence](./README.md#capture-sequence)

### Preconditions

- Authorisation exists and is currently `AUTHORISED` (capture from any other status is rejected).
- Capture request provides `idempotencyKey`, scoped to `(authorisationId, idempotencyKey)`.

### Expected behavior

1. Validate legal state transition (`AUTHORISED -> CAPTURED`); any other current status raises
   `AuthorisationIllegalStateException` (`409`, `INVALID_AUTHORISATION_STATE`) — **except** when
   the authorisation is already `CAPTURED` and the same capture `idempotencyKey` is retried, which
   replays the prior response instead.
2. Convert reserved balance to a settled effect: `reservedBalance -= amount` (this project only
   supports **full capture** — there is no partial-capture support).
3. Persist the capture state + capture event + outbox event atomically.

### Outputs

- Authorisation state becomes `CAPTURED` (terminal — no further transitions are possible).
- Reserved funds are consumed permanently (`reservedBalance` reduced; unlike reverse, funds do
  **not** return to `availableBalance`).
- `AUTHORISATION_CAPTURED` outbox event is available for ledger projection.

---

## 3) Reverse flow

**Reference diagram:** [Reverse Sequence](./README.md#reverse-sequence)

### Current status

Fully implemented (this is not a placeholder) — reverse releases a previously reserved amount back
to the available balance.

### Preconditions

- Authorisation exists and is currently `AUTHORISED` (reverse from any other status is rejected,
  except a replay of an already-`REVERSED` authorisation with the same `idempotencyKey`).
- Reverse request provides `idempotencyKey` and `reasonCode`
  (`AuthorisationEventReason`, e.g. `CUSTOMER_REQUEST`/`MERCHANT_REQUEST`), scoped to
  `(authorisationId, idempotencyKey)`.

### Expected behavior

1. Validate legal state transition (`AUTHORISED -> REVERSED`).
2. Release the reserved amount: `reservedBalance -= amount`, `availableBalance += amount` — unlike
   capture, the funds return to `availableBalance` since they were never actually debited from it,
   only earmarked.
3. Persist reversal state + reversal event + outbox event atomically.
4. Replay-safe via the same idempotency pattern as authorise/capture
   (`(authorisationId, idempotencyKey)`, payload-fingerprinted, race-recovered on a concurrent
   duplicate).

### Outputs

- Authorisation state becomes `REVERSED` (terminal).
- Reserved funds return to `availableBalance`.
- `AUTHORISATION_REVERSED` outbox event is available for ledger projection.

---

## 4) Ledger projection (consume side)

**Reference diagram:** [Event Consuming Sequence](./README.md#event-consuming-sequence)

ledger-service consumes `auth.events` and projects three of the four event types into
ledger entries + an event log:

| Event type                 | Ledger-side handling                                                                                           |
|----------------------------|----------------------------------------------------------------------------------------------------------------|
| `AUTHORISATION_AUTHORISED` | Projected (`AuthorisationAuthorisedHandler`)                                                                   |
| `AUTHORISATION_CAPTURED`   | Projected (`AuthorisationCapturedHandler`)                                                                     |
| `AUTHORISATION_REVERSED`   | Projected (`AuthorisationReversedHandler`)                                                                     |
| `AUTHORISATION_DECLINED`   | Intentionally skipped — no ledger entry needed since balances were never touched                               |

Every projected event is deduplicated by `eventId` via `processed_event` (`INSERT ... ON CONFLICT
DO NOTHING`), giving effectively-once projection over Kafka's at-least-once delivery. A genuinely
unrecognized event type raises a non-retryable `IllegalArgumentException`, routed straight to the
`auth.events.ledger.dlt` dead-letter topic; other runtime failures are retried 3× with a 2s fixed
backoff before falling back to the DLT.

---

## Step-to-data/event mapping

| Flow step                  | Primary write(s)                                                | Outbox event               | Ledger-side expectation                   |
|----------------------------|-----------------------------------------------------------------|----------------------------|-------------------------------------------|
| Authorise → funds reserved | authorisation (`AUTHORISED`) + account reservation              | `AUTHORISATION_AUTHORISED` | Ledger entry + event log projected        |
| Authorise → declined       | authorisation (`DECLINED`) only (+ account lock, if applicable) | `AUTHORISATION_DECLINED`   | Skipped — no ledger entry needed (see section 4) |
| Capture accepted           | authorisation (`CAPTURED`) + reserved-balance debit             | `AUTHORISATION_CAPTURED`   | Ledger entry + event log projected        |
| Reverse accepted           | authorisation (`REVERSED`) + reserved→available release         | `AUTHORISATION_REVERSED`   | Ledger entry + event log projected        |

---

## Idempotency expectations (MVP)

- Authorisation endpoint: implemented (Redis-cached + DB-unique-constraint-backed).
- Capture endpoint: implemented, same pattern.
- Reverse endpoint: implemented, same pattern.
- `POST /accounts` and `POST /accounts/{id}/deposits` are test/setup conveniences (not on the
  payment-critical path); extending idempotency to them is tracked on the roadmap.

See also: [`../api/README.md#idempotency`](../api/README.md#idempotency) and
[Idempotency Generic Sequence](./README.md#idempotency-generic-sequence) for the generic replay/conflict decision
logic shared by every idempotent endpoint.

---

## Observability hooks

For each lifecycle step, verify:

- Trace span continuity across request → fraud-service call → persistence → outbox publish
  (OpenTelemetry traces exported to Tempo; `traceparent` is persisted on the outbox row and
  re-attached as a Kafka header so the ledger consumer span links back to the original request).
- Counters for success/failure/decline-reason/replay/conflict — see `auth_requests_total`,
  `auth_authorisations_total{status,reason}`, `auth_concurrency_conflict_total{operation,type}`,
  `auth_idempotency_cache_total{result}` (`auth-service`), and `fraud_decisions_total{outcome}` /
  `fraud_check_duration_seconds{outcome}` (`fraud-service`).
- Latency for request handling, the fraud-service round trip
  (`auth_fraud_check_duration_seconds`), and outbox publish lag
  (`auth_outbox_publish_lag_seconds`).

See also:

- [Payment Platform MVP Progress](../roadmap.md) — Observability section for the full metric/dashboard list.
- [Operational Runbook](../development/runbook.md) — operational commands (DB, Redis,
  trace queries).

---

## MVP notes for reviewers

This lifecycle documentation demonstrates correctness of core flows for a personal MVP.
Authorise, capture, and reverse are all fully implemented (not placeholders). See
[Payment Platform MVP Progress](../roadmap.md) for planned enhancements beyond this MVP scope.
