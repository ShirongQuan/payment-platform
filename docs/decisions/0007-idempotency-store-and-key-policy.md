# ADR 0007: Idempotency Store Strategy and Key Policy (Auth/Fraud)

## Table of Contents

- [Context](#context)
- [Decision](#decision)
    - [Storage choice: Redis (auth-service), DB unique constraint (fraud-service)](#storage-choice-redis-auth-service-db-unique-constraint-fraud-service)
    - [Key scope](#key-scope)
    - [TTL](#ttl)
    - [Replay / conflict semantics](#replay--conflict-semantics)
- [Consequences](#consequences)
- [Alternatives Considered](#alternatives-considered)
- [Related](#related)

- Status: Accepted
- Date: 2026-08-13

## Context

ADR 0005 defines the correctness rules that idempotency must satisfy (safe replay, conflict detection, scope
boundaries). This ADR covers the implementation policy: where idempotency state is stored, how keys are scoped,
what TTL applies, and how replay/conflict is resolved at the storage layer for `auth-service` and `fraud-service`.

Builds on correctness constraints from 0005.

## Decision

### Storage choice: Redis (auth-service), DB unique constraint (fraud-service)

- `auth-service` (`IdempotencyService`) stores the **computed response payload** in Redis via
  `StringRedisTemplate`, keyed per operation, and returns it verbatim on replay.
- `fraud-service` (`FraudEvaluationEntity` / `FraudEvaluationRepository`) does not use a Redis idempotency cache.
  Instead it relies on a **DB unique constraint** on `(accountId, idempotencyKey)` plus a `tryInsertPending`-style
  atomic insert to claim the key, then updates the row with the final decision once evaluation completes.

Rationale for using service-specific mechanisms:

- `auth-service` uses Redis as the low-latency replay path for bounded-lifetime response payloads, while persisted domain state and database constraints remain the durable correctness boundary described in ADR 0005.
- `fraud-service` owns durable fraud-evaluation rows, so the same service-owned table stores the decision and enforces the idempotency claim boundary without introducing a second store for that responsibility.

### Key scope

- `auth-service`: `{operationType}:idempotency:{scopeId}:{idempotencyKey}`
    - `operationType` — `AUTHORISE` / `CAPTURE` / `REVERSE` (lower-cased)
    - `scopeId` — `accountId` for authorise, `authorisationId` for capture/reverse
    - this mirrors the DB uniqueness boundary from ADR 0005 (`account_id + event_type + idempotency_key`)
- `fraud-service`: unique index on `(account_id, idempotency_key)` — one fraud evaluation per account per key

### TTL

- `auth-service`: configurable via `idempotency.ttl-hours` (default `24` hours). Long enough to cover realistic
  client retry windows (timeouts, gateway retries, manual resubmission) without keeping replay state indefinitely.
- `fraud-service`: no TTL — the row is retained as part of the durable fraud evaluation history (audit/rule tuning),
  so cleanup is a data-retention concern rather than an idempotency-window concern.

### Replay / conflict semantics

- `auth-service`:
    - cache hit on `(operationType, scopeId, idempotencyKey)` -> deserialize and return the stored response
      (safe replay, no re-execution)
    - cache miss -> execute the operation, then `store(...)` the response with the configured TTL
    - a **different payload** under the same key is detected at the domain/DB layer (unique constraint /
      `IdempotencyConflictException`), not by the Redis cache itself — Redis only caches the *result*, it does not
      validate payload equality
- `fraud-service`:
    - `tryInsertPending` performs an atomic claim insert; if the unique constraint is violated, another
      request already owns that key
    - if the existing row is still `PENDING`, the caller receives `FraudEvaluationInProgressException` (concurrent
      in-flight request with the same key)
    - if the existing row is complete, the stored decision is returned as the replay result

## Consequences

Positive:

- fast replay path for `auth-service` (single Redis GET) without re-running business logic or hitting Postgres
- `fraud-service` gets replay + audit history from a single write, no dual-write consistency problem
- TTL keeps Redis memory bounded to realistic retry windows

Trade-offs:

- the two services use different storage mechanisms, so their idempotency implementation details need to remain clearly documented even though they implement the same replay/conflict principles
- the Redis replay entry in `auth-service` has a bounded lifetime; after expiry, a later request is evaluated against persisted state and the database-level guards from ADR 0005 continue to protect the correctness boundary
- if Redis is unavailable, `auth-service` loses the low-latency replay path and falls through to normal execution and database-level conflict handling, increasing database work without making Redis the sole correctness mechanism

## Alternatives Considered

- store idempotency response payloads in Postgres instead of Redis (rejected: adds write load and cleanup jobs for
  what is fundamentally a short-lived cache; Redis TTL handles expiry natively)
- use Redis for fraud-service idempotency claims too (rejected: fraud evaluations are already durable rows; a
  second cache would duplicate state and add a consistency question between cache and DB)
- global idempotency key (no scope) across all operations (rejected — see ADR 0005 section on scope)

## Related

- [ADR 0005: Idempotency and Concurrency Rules for Authorisation Flows](./0005-idempotency-and-concurrency.md) — see
  also 0005 for correctness rules this policy implements
- [ADR 0006: Use Redis for Sliding-Window Fraud Velocity Checks](0006-use-redis-for-sliding-window-fraud-velocity-checks.md)
- `auth-service/src/main/java/org/example/auth/idempotency/IdempotencyService.java`
- `fraud-service/src/main/java/org/example/fraud/infrastructure/FraudEvaluationEntity.java`
- `shared-spring-lib/src/main/java/org/example/shared/idempotency/RequestHashing.java`

