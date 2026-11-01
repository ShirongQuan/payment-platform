# ADR 0005: Idempotency and Concurrency Rules for Authorisation Flows

## Table of Contents

- [1) Context](#1-context)
- [2) Decision](#2-decision)
- [3) Scope of Idempotency](#3-scope-of-idempotency)
- [4) Request Replay Semantics](#4-request-replay-semantics)
- [5) Concurrency Handling](#5-concurrency-handling)
- [6) Downstream Event Idempotency](#6-downstream-event-idempotency)
- [7) Error Handling](#7-error-handling)
- [8) Design Boundaries and Trade-offs](#8-design-boundaries-and-trade-offs)
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

Note: this DB boundary always scopes on `account_id`, including for capture/reverse. ADR 0007's Redis
fast-path cache additionally scopes capture/reverse on `authorisationId` (finer-grained than the DB tuple) —
see [ADR 0007: Key scope](./0007-idempotency-store-and-key-policy.md#key-scope) for why that divergence exists
and why it does not weaken the correctness boundary described here.

## 4) Request Replay Semantics

The API behaviour is deterministic for retries:

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

Two distinct race scenarios exist under concurrent requests, resolved by two different mechanisms. They
must not be conflated: only one of them can legitimately resolve to a replayed response.

**A. Same-key race (idempotency race)** — two requests carrying the *same* idempotency key for the same
operation (and same scope: `account_id`/`event_type`, or additionally `authorisation_id` at the Redis
fast-path layer) race to commit:

- both may pass earlier checks (cache miss, DB pre-check), but the unique constraint
  (`uq_authorisation_event_account_eventtype_idempotency`) allows only one insert to succeed
- the loser's insert fails the unique constraint; `auth-service` wraps this persistence failure (for
  example, `DataIntegrityViolationException`) into an explicit `ConcurrentIdempotencyRaceException`
- recovery: re-read the now-committed row for that key
    - if the winning request's payload is equivalent (same semantic request) -> return the replayed
      success response, no duplicate side effect
    - if it is not equivalent -> return `IdempotencyConflictException` (`409 Conflict`)

**B. Different-key race on the same account (optimistic-lock conflict)** — two *different*, legitimately-keyed
requests race to mutate the same account's balance (for example, two concurrent authorise calls with different
idempotency keys, or an authorise racing a reverse on the same account):

- the `@Version` column on `AccountEntity` detects the lost update as an
  `ObjectOptimisticLockingFailureException`
- `auth-service` translates this into `AccountConcurrencyConflictException` -> `409 Conflict`
- there is **no replay** here: the two requests are semantically different (different idempotency keys), so
  the loser is simply told to retry/conflict, never handed someone else's result

Only scenario A can legitimately resolve to "return replayed success" — because only a same-key race
means the two racing requests might represent the identical logical operation. Scenario B's losing request
is, by definition, a different operation (different key) than the winner, so re-reading committed state and
replaying would silently return the wrong response; it must always surface as a conflict for the caller to
retry.

For same-key races, `auth-service` translates persistence uniqueness failures into explicit idempotency-race handling
and resolves replay or conflict from committed records. Different-key optimistic-lock failures return a concurrency
conflict rather than replaying another operation's response.

## 6) Downstream Event Idempotency

For Kafka-driven ledger projection:

- each emitted event carries a unique `eventId`
- `ledger-service` inserts into `processed_event` using `on conflict do nothing`
- first insert processes the event; duplicate `eventId` is ignored
- ledger ingestion is order-tolerant for duplicate delivery (at-least-once)

This gives effectively-once projection behaviour per unique `eventId` without distributed transactions.

## 7) Error Handling

Auth API maps failures to stable HTTP semantics:

- `IdempotencyConflictException` -> `409 Conflict`
- `AuthorisationIllegalStateException` -> `409 Conflict`
- `AccountConcurrencyConflictException` -> `409 Conflict` (different-key optimistic-lock race on the same
  account, see [5) Concurrency Handling](#5-concurrency-handling) scenario B — distinct from
  `IdempotencyConflictException`, which is a same-key/different-payload conflict)
- `AccountNotFoundException` / `AuthorisationNotFoundException` -> `404 Not Found`
- request validation failures (bean validation / malformed payload) -> `400 Bad Request`

## 8) Design Boundaries and Trade-offs

The design places correctness controls at the boundary where each concern is owned:

- `auth-service` uses operation-scoped request idempotency, database uniqueness, and optimistic locking for payment
  commands
- `ledger-service` uses `eventId` deduplication at the asynchronous consumer boundary
- concurrency is resolved through service-owned database state rather than a distributed lock shared across services
- the platform targets recoverable at-least-once event delivery with consumer deduplication rather than requiring an
  end-to-end distributed exactly-once transaction
- idempotency rules remain explicit per operation flow, keeping replay and conflict semantics aligned with each payment
  command
- partial capture and partial reverse are planned lifecycle extensions; see
  [roadmap: Production Considerations](../roadmap.md#5-future-engineering-directions)

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

See also [ADR 0007: Idempotency Store Strategy and Key Policy (Auth/Fraud)](./0007-idempotency-store-and-key-policy.md)
for storage/policy (Redis vs DB, TTL, key scope, replay/conflict implementation details).

- [ADR 0001: Use Kafka + Transactional Outbox](./0001-use-kafka-outbox.md)
- [ADR 0002: Support Full Capture and Full Reverse](./0002-full-capture-only.md)
- [ADR 0003: Store Both Raw and Normalised Ledger Events](./0003-ledger-raw-and-normalised-events.md)
- [ADR 0004: Use Separate Main Event and DLT Topics](./0004-use-event-and-dlt-topics.md)
- [ADR 0007: Idempotency Store Strategy and Key Policy (Auth/Fraud)](./0007-idempotency-store-and-key-policy.md)
- [ADR 0010: Database Concurrency Approach (Optimistic vs Pessimistic Locking)](./0010-database-concurrency-approach.md)


