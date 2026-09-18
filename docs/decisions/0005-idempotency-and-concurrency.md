# ADR 0005: Idempotency and Concurrency Rules for Authorisation Flows

## Table of Contents

- [1) Context](#1-context)
- [2) Decision](#2-decision)
- [3) Scope of Idempotency](#3-scope-of-idempotency)
- [4) Request Replay Semantics](#4-request-replay-semantics)
- [5) Concurrency Handling](#5-concurrency-handling)
- [6) Downstream Event Idempotency](#6-downstream-event-idempotency)
- [7) Error Handling](#7-error-handling)
- [8) Trade-offs](#8-trade-offs)
- [9) Alternatives Considered](#9-alternatives-considered)
- [10) Consequences](#10-consequences)
- [Related](#related)

- Status: Accepted
- Date: 2026-08-06

## 1) Context

Idempotency and concurrency controls are required because:

- payment commands can be retried by clients, gateways, and network intermediaries
- duplicate execution could double-reserve, double-capture, or double-reverse funds
- concurrent requests may race on the same account or authorisation state
- Kafka delivery is at-least-once, so downstream consumers may receive duplicates

Without clear replay rules and database protections, balance integrity and ledger projections can diverge.

## 2) Decision

The platform uses request-level idempotency in `auth-service` plus event-level deduplication in `ledger-service`.

- `authorise`, `capture`, and `reverse` require idempotency keys
- same key + same request returns the previously computed response
- same key + different request returns `IdempotencyConflictException`
- different key after an operation is already completed returns business/state conflict (for example, capture on already
  captured authorisation)
- ledger deduplicates consumed events by `event_id` in `processed_event`
- optimistic locking and unique constraints are the final concurrency guard under race conditions

## 3) Scope of Idempotency

Idempotency uniqueness is operation-scoped, not global.

- authorise: scoped to `account_id + event_type (AUTHORISED/DECLINED) + idempotency_key`
- capture: scoped to `account_id + event_type (CAPTURED) + idempotency_key`
- reverse: scoped to `account_id + event_type (REVERSED) + idempotency_key`

Current DB index in `auth-service` enforces this boundary:

- `uq_authorisation_event_account_eventtype_idempotency` on
  `authorisation_event(account_id, event_type, idempotency_key)`

## 4) Request Replay Semantics

The API behavior is deterministic for retries:

- safe retry (same key, same semantic payload): return previous response, no duplicate side effects
- changed request with same key: reject with idempotency conflict
- retry after client timeout: either
    - request had already committed -> replay returns prior response, or
    - request failed/rolled back -> operation is evaluated again
- retry after server processed successfully but client missed response: replay returns prior response from stored
  state/event

### Examples

1. Safe retry with same idempotency key and same payload
    - Request A: `POST /authorisations` with `idempotencyKey=auth-123`, amount `10.00`, currency `USD`
    - Request B (retry): same key and same payload after client timeout
    - Result: `200 OK` replay of the original authorisation response; no additional reserve side effect

2. Same key with changed payload
    - Request A: `POST /authorisations` with `idempotencyKey=auth-123`, amount `10.00`
    - Request B: same key but amount `20.00`
    - Result: `409 Conflict` (`IdempotencyConflictException`)

3. Different key after operation already completed
    - Request A: `POST /authorisations/{id}/capture` with `idempotencyKey=cap-001` succeeds
    - Request B: `POST /authorisations/{id}/capture` with `idempotencyKey=cap-002` after status is already `CAPTURED`
    - Result: `409 Conflict` (`AuthorisationIllegalStateException`)

## 5) Concurrency Handling

When two requests race at the same time:

- both may pass early checks, but DB constraints/version checks decide the winner
- unique constraints prevent duplicate idempotency tuples from committing twice
- optimistic locking/version updates protect state transitions on shared rows
- loser path is recovered by re-reading persisted state/event:
    - if equivalent request already won, return replayed success
    - if conflicting request won, return conflict/error

`auth-service` wraps persistence races (for example, `DataIntegrityViolationException`) into explicit idempotency race
handling and resolves the final API response from committed records.

## 6) Downstream Event Idempotency

For Kafka-driven ledger projection:

- each emitted event carries a unique `eventId`
- `ledger-service` inserts into `processed_event` using `on conflict do nothing`
- first insert processes the event; duplicate `eventId` is ignored
- ledger ingestion is order-tolerant for duplicate delivery (at-least-once)

This gives effectively-once projection behavior per unique `eventId` without distributed transactions.

## 7) Error Handling

Auth API maps failures to stable HTTP semantics:

- `IdempotencyConflictException` -> `409 Conflict`
- `AuthorisationIllegalStateException` -> `409 Conflict`
- `AccountNotFoundException` / `AuthorisationNotFoundException` -> `404 Not Found`
- request validation failures (bean validation / malformed payload) -> `400 Bad Request`

## 8) Trade-offs

Intentional non-goals in the current design:

- no distributed locking between service instances
- no exactly-once guarantee end-to-end across HTTP, DB, Kafka, and consumers
- no partial capture / partial reverse support yet (full operation only)
- no generic cross-endpoint idempotency framework yet; rules are implemented per operation flow

## 9) Alternatives Considered

Alternatives evaluated but not chosen:

- header-only idempotency store detached from domain event history
- event-level dedup only, without request-level idempotency in auth commands
- account-level global uniqueness for all operations (too restrictive)
- distributed lock manager (higher complexity/latency and operational burden)

## 10) Consequences

Benefits:

- safe client retries with deterministic outcomes
- reduced risk of duplicate balance mutations
- clearer audit trail via authorisation events + outbox + ledger event log

Costs:

- extra schema constraints and replay logic complexity
- additional code paths for race recovery and conflict semantics
- need to persist enough operation context to rebuild replay responses

## Related

See also `docs/decisions/0007-idempotency-store-and-key-policy.md` for storage/policy (Redis vs DB, TTL, key
scope, replay/conflict implementation details).

- `docs/decisions/0001-use-kafka-outbox.md`
- `docs/decisions/0002-full-capture-only.md`
- `docs/decisions/0003-ledger-raw-and-normalized-events.md`
- `docs/decisions/0004-use-event-and-dlt-topics.md`
- `docs/decisions/0007-idempotency-store-and-key-policy.md`
- `docs/decisions/0010-database-concurrency-approach.md`


