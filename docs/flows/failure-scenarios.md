# Failure Scenarios

## Table of Contents

- [Purpose](#purpose)
- [Scope](#scope)
- [Failure scenario catalog](#failure-scenario-catalog)
    - [1) Duplicate API request (same idempotency key)](#1-duplicate-api-request-same-idempotency-key)
    - [2) Client timeout after server commit](#2-client-timeout-after-server-commit)
    - [3) Outbox publish transient failure (producer side)](#3-outbox-publish-transient-failure-producer-side)
    - [4) Duplicate event delivery to ledger consumer](#4-duplicate-event-delivery-to-ledger-consumer)
    - [5) Invalid state transition (business rule violation)](#5-invalid-state-transition-business-rule-violation)
    - [6) Partial downstream failure during processing](#6-partial-downstream-failure-during-processing)
    - [7) Fraud decline and account auto-lock](#7-fraud-decline-and-account-auto-lock)
- [Scenario-to-diagram map](#scenario-to-diagram-map)
- [Quick triage checklist](#quick-triage-checklist)
- [Current scope notes](#current-scope-notes)

## Purpose

Document expected behavior when key runtime failures occur, and how to diagnose/recover during demo and development.

## Scope

- API-level retries/duplicates
- Producer-side outbox publishing failures
- Consumer-side duplicate or malformed events
- Partial failure windows between commit and response/delivery
- Fraud pre-check decline and the resulting account auto-lock side effect

---

## Failure scenario catalog

## 1) Duplicate API request (same idempotency key)

### Trigger

Client retries the same mutating request due to timeout/network uncertainty.

### Expected behavior

- Same key + same payload: return stored prior result (safe replay).
- Same key + different payload: return conflict according to idempotency policy.

### What to verify

- No duplicate reservation/capture side effects.
- Idempotency record is reused, not recreated.

### Where to inspect

- API logs for idempotency lookup/replay path
- Idempotency metrics (replay/conflict counters)
- Trace showing replay branch

---

## 2) Client timeout after server commit

### Trigger

Server commits DB transaction, but client never receives response (connection timeout/reset).

### Expected behavior

- Client retry with same idempotency key returns committed result.
- No additional writes/effects on retry.

### What to verify

- Original write exists.
- Retry follows replay path.
- Exactly one business effect recorded.

---

## 3) Outbox publish transient failure (producer side)

### Trigger

Kafka temporarily unavailable or publish attempt fails.

### Expected behavior

- Outbox row remains/retries according to publisher policy.
- Event is retried and eventually published.
- No event loss; duplicates are acceptable if consumer is dedupe-safe.

### Where to inspect

- [Event Publishing Sequence](./README.md#event-publishing-sequence)
- Outbox table status transitions
- Publisher retry/failure metrics and logs

### Operator action (MVP)

1. Confirm Kafka connectivity.
2. Check outbox backlog growth.
3. Confirm retry loop is active.
4. Reprocess stuck records if manual command/tooling exists.

See [Outbox Backlog Recovery](./outbox-backlog-recovery.md) and its companion flowchart
[Outbox Backlog Recovery Flow](./README.md#outbox-backlog-recovery-flow) for the full
detect → triage → retry → DLQ/manual-replay decision logic, including exactly how to manually
requeue a terminally `FAILED` outbox row.

---

## 4) Duplicate event delivery to ledger consumer

### Trigger

At-least-once delivery causes re-delivery of same message.

### Expected behavior

- Consumer deduplicates by event/message key.
- Projection/ledger writes remain idempotent.

### Where to inspect

- [Event Consuming Sequence](./README.md#event-consuming-sequence)
- Dedup store/table entries
- Consumer logs for duplicate detection path

---

## 5) Invalid state transition (business rule violation)

### Trigger

Capture/reverse requested from an illegal current state (e.g. capturing an already-`CAPTURED` or
`DECLINED` authorisation with a *different* idempotency key than the one that completed it; reverse
requested on a `CAPTURED` or `DECLINED` authorisation).

### Expected behavior

- Request rejected with `409 Conflict` (`AuthorisationIllegalStateException`,
  `errorCode=INVALID_AUTHORISATION_STATE`).
- No balance/ledger side effects.
- No outbox event emitted.
- Note: if the *same* idempotency key that completed the original capture/reverse is retried, this
  is **not** an invalid-state error — it's a safe replay (see scenario 1/[Idempotency Generic Sequence](./README.md#idempotency-generic-sequence)).
  Only a *different* key against an already-terminal authorisation hits this path.

### What to verify

- Domain validation fired before mutation (`AuthorisationTransactionalExecutorImpl` checks status
  before touching the account).
- No persistence changes except the domain error itself.

### Where to inspect

- [Capture Sequence](./README.md#capture-sequence), [Reverse Sequence](./README.md#reverse-sequence)
- `AuthorisationIllegalStateException` in logs/traces

---

## 6) Partial downstream failure during processing

### Trigger

One component succeeds but follow-on action fails (e.g., event publish deferred).

### Expected behavior

- Atomic boundary respected for core DB changes (authorisation/account/event rows commit together
  in one transaction; the outbox row is written in that same transaction).
- Deferred side effects (the actual Kafka publish) recovered via outbox retry — see
  [Event Publishing Sequence](./README.md#event-publishing-sequence) and
  [Outbox Backlog Recovery](./outbox-backlog-recovery.md).
- System converges automatically in the common (transient) case; a terminally `FAILED` outbox row
  follows the manual replay procedure documented there.

---

## 7) Fraud decline and account auto-lock

### Trigger

`POST /authorisations` is evaluated by fraud-service's synchronous risk check and comes back
`DECLINE`, either because the computed risk score crossed the decline threshold, or because
fraud-service was unreachable/timed out and no fail-open policy applied.

### Expected behavior

- Authorisation is persisted as `DECLINED` with `reasonCode=FRAUD_DECLINED` (or
  `FRAUD_SERVICE_UNAVAILABLE` reasoning surfaced via fraud-service's own reasons list when the
  service was unreachable) — no funds are reserved.
- If fraud-service also recommends locking the account (very high risk score, or a
  repeated-decline pattern within the trailing window) **and** the account is currently `ACTIVE`,
  auth-service flips the account to `LOCKED` in the *same* transaction as the decline.
- Once `LOCKED`, subsequent authorise attempts short-circuit with `reasonCode=ACCOUNT_LOCKED`
  before any fraud-service call is made (see the account-active gate in
  [Authorise Sequence](./README.md#authorise-sequence)).
- Unlocking a `LOCKED` account back to `ACTIVE` is a manual, operator-driven action today; an
  automated/API-driven unlock flow is tracked on the roadmap.

### What to verify

- `auth_authorisations_total{status="DECLINED",reason="FRAUD_DECLINED"}` incremented.
- Account row's `status` column flips to `LOCKED` when the lock-recommendation conditions are met.
- The `DECLINED` authorisation event captures the decline; a dedicated event for the lock
  transition itself is a future enhancement.

### Where to inspect

- [Authorise Sequence](./README.md#authorise-sequence)
- fraud-service's `fraud_decisions_total{outcome}` / `fraud_check_duration_seconds{outcome}`
- [Fraud Service API](../api/fraud-api.md)

---

## Scenario-to-diagram map

| Scenario                          | Primary diagram(s)                                                          |
|-----------------------------------|-----------------------------------------------------------------------------|
| Duplicate API request             | [Authorise Sequence](./README.md#authorise-sequence), [Capture Sequence](./README.md#capture-sequence), [Idempotency Generic Sequence](./README.md#idempotency-generic-sequence) |
| Timeout after commit              | [Authorise Sequence](./README.md#authorise-sequence), [Capture Sequence](./README.md#capture-sequence) |
| Publish retry/failure             | [Event Publishing Sequence](./README.md#event-publishing-sequence), [Outbox Backlog Recovery Flow](./README.md#outbox-backlog-recovery-flow) |
| Duplicate consume                 | [Event Consuming Sequence](./README.md#event-consuming-sequence)                    |
| Invalid transition                | [Capture Sequence](./README.md#capture-sequence), [Reverse Sequence](./README.md#reverse-sequence) |
| Fraud decline / account auto-lock | [Authorise Sequence](./README.md#authorise-sequence)              |

---

## Quick triage checklist

1. Identify request/event id (correlation id, idempotency key, event id).
2. Confirm business state in DB.
3. Confirm outbox record status.
4. Confirm Kafka publish/consume evidence.
5. Confirm dedupe/replay path activated as expected.

---

## Current scope notes

- Reverse and capture are both fully implemented (not placeholders) — see
  [Capture Sequence](./README.md#capture-sequence) / [Reverse Sequence](./README.md#reverse-sequence)
  for the actual persisted flow.
- Idempotency is implemented on the payment-critical path (authorise/capture/reverse/fraud
  check). See [Payment Platform MVP Progress](../roadmap.md) for planned enhancements beyond this MVP scope
  (account unlock automation, `POST /accounts` idempotency, automated outbox DLQ replay).
