# Flow Diagrams

## Table of Contents

- [Start Here (Recommended Reading Order)](#start-here-recommended-reading-order)
- [Documents in this folder](#documents-in-this-folder)
- [Diagram Index](#diagram-index)
- [Quick Guidance](#quick-guidance)
- [MVP Scope Note](#mvp-scope-note)

This folder contains Mermaid sequence diagrams and companion documentation for key runtime flows across `auth-service`
and `ledger-service`.

## Start Here (Recommended Reading Order)

1. [Payment Lifecycle](./payment-lifecycle.md)
2. `authorise-sequence.mmd`
3. `capture-sequence.mmd`
4. `idempotency-generic.mmd`
5. `event-publishing-sequence.mmd`
6. `event-consuming.mmd`
7. `reverse-sequence.mmd`
8. [Failure Scenarios](./failure-scenarios.md)
9. [Outbox Backlog Recovery](./outbox-backlog-recovery.md) + `outbox-backlog-recovery-flow.mmd`

---

## Documents in this folder

- [payment-lifecycle.md](./payment-lifecycle.md)  
  Canonical business flow (`auth -> reserve -> capture -> reverse/refund`), state transitions, and event/data mapping.

- [failure-scenarios.md](./failure-scenarios.md)  
  Expected behavior for duplicates, timeouts, publish/consume failures, and invalid transitions.

- [outbox-backlog-recovery.md](./outbox-backlog-recovery.md)  
  How to detect, triage, and recover a growing auth-service outbox backlog or a terminally `FAILED`
  outbox row (manual replay), including the current lack of an automated dead-letter/replay path.

---

## Diagram Index

### `authorise-sequence.mmd`

Use when working on **initial authorisation** behavior.

Covers:

- request handling
- idempotency checks
- account reserve logic
- event persistence
- outbox enqueue

### `capture-sequence.mmd`

Use when working on **capture** behavior.

Covers:

- legal-state validation
- idempotent replay/conflict behavior
- reserved balance capture
- capture outbox event creation

### `reverse-sequence.mmd`

Use when working on **reverse** behavior.

Covers:

- legal-state validation (`AUTHORISED` required; already-`REVERSED` triggers idempotent replay)
- idempotent replay/conflict behavior (same pattern as capture)
- reserved-balance release (`reservedBalance` only; `availableBalance` untouched)
- reverse outbox event creation

### `idempotency-generic.mmd`

Use when explaining or designing **idempotency behavior generically**, independent of any single
endpoint. Applies to every idempotency-keyed mutating endpoint across the platform (auth-service's
authorise/capture/reverse and fraud-service's `/fraud/check`).

Covers:

- first request for a given `(scopeId, idempotencyKey)`
- retry with the same key + same payload -> safe cached/durable replay (no duplicate side effect)
- retry with the same key + a different payload -> `409 IDEMPOTENCY_CONFLICT`
- concurrent first-time requests racing on the same key -> loser recovers the winner's committed
  response instead of failing or duplicating the operation

### `event-publishing-sequence.mmd`

Use for **auth-service outbox publishing** concerns.

Covers:

- polling
- claiming
- retry/fail handling
- Kafka publish path

### `event-consuming.mmd`

Use for **ledger-service consume/projection** concerns.

Covers:

- header parsing
- routing
- deduplication
- projection writes

### `outbox-backlog-recovery-flow.mmd`

Use when an outbox backlog is growing or rows are landing in `FAILED`, and you need the
decision logic for how to respond. Companion flowchart for
[outbox-backlog-recovery.md](./outbox-backlog-recovery.md).

Covers:

- **detect** — backlog/lag/failed-attempt metrics vs. querying the outbox table directly
- **triage** — distinguishing a self-healing stale claim from a genuinely exhausted retry budget,
  and classifying the root cause (transient infra vs. bad data)
- **retry** — the automatic claim → publish → backoff loop
- **DLQ/manual replay** — the manual requeue path for terminal `FAILED` rows (no automated
  dead-letter/replay mechanism exists today)

### `payment-lifecycle-state.mmd` / `account-lock-state.mmd`

Use alongside [payment-lifecycle.md](./payment-lifecycle.md#lifecycle-at-a-glance) for the
authorisation and account-level state diagrams at a glance (a closely related, slightly more
detailed pair of the same two diagrams also lives in
[`../domain/state-machine.md`](../domain/state-machine.md#diagram)).

---

## Quick Guidance

- For API/business logic changes, start with:
    - `authorise-sequence.mmd`
    - `capture-sequence.mmd`

- For understanding idempotency replay/conflict rules generically (not tied to one endpoint), use:
    - `idempotency-generic.mmd`

- For producer-side delivery reliability/debugging, use:
    - `event-publishing-sequence.mmd`

- For consumer-side duplicate handling or projection discrepancies, use:
    - `event-consuming.mmd`

- For diagnosing/recovering an outbox backlog or a terminally `FAILED` outbox row, use:
    - `outbox-backlog-recovery.md` + `outbox-backlog-recovery-flow.mmd`

- Use `reverse-sequence.mmd` for the reverse (release) flow — implemented, not a placeholder.

---

## MVP Scope Note

These flow docs are written for a **demo-focused MVP**:

- core flows are implemented and demonstrable,
- some production-hardening behavior is intentionally deferred.

See roadmap/scope docs for production considerations.