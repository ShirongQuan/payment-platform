# ADR 0007: Idempotency Store Strategy and Key Policy (Auth/Fraud)

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

Rationale for the split:
- `auth-service` idempotency responses are ephemeral, replay-only payloads with a bounded lifetime — Redis is a
  natural fit (fast, TTL-native, no need for durable audit history beyond the ledger/outbox trail already covered by
  ADR 0001/0003).
- `fraud-service` evaluations are also written as durable rows for audit/rule-history purposes, so the same table
  that stores the evaluation result is used to enforce the idempotency boundary; no separate cache is required.

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
- two different idempotency mechanisms across services increases the amount of implementation detail contributors
  need to hold in their heads
- Redis-cached replay in `auth-service` is best-effort: if the key expires before a very late retry, the operation
  is re-evaluated against current DB state rather than replayed (acceptable given ADR 0005's DB-level guards still
  prevent duplicate side effects)
- Redis outage removes the fast replay path in `auth-service` (falls through to normal execution/DB-level conflict
  handling, not a hard failure, but higher latency/load)

## Alternatives Considered

- store idempotency response payloads in Postgres instead of Redis (rejected: adds write load and cleanup jobs for
  what is fundamentally a short-lived cache; Redis TTL handles expiry natively)
- use Redis for fraud-service idempotency claims too (rejected: fraud evaluations are already durable rows; a
  second cache would duplicate state and add a consistency question between cache and DB)
- global idempotency key (no scope) across all operations (rejected — see ADR 0005 section on scope)

## Related

- `docs/decisions/0005-idempotency-and-concurrency.md` — see also 0005 for correctness rules this policy implements
- `docs/decisions/0006-redis-sliding-window-rate-limit.md`
- `auth-service/src/main/java/org/example/auth/idempotency/IdempotencyService.java`
- `fraud-service/src/main/java/org/example/fraud/infrastructure/FraudEvaluationEntity.java`
- `shared-spring-lib/src/main/java/org/example/shared/idempotency/RequestHashing.java`

