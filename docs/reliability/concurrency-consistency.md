# Concurrency & Consistency

## Table of Contents

- [Consistency Goals](#consistency-goals)
- [Concurrency Model per Flow](#concurrency-model-per-flow)
- [Isolation Level](#isolation-level)
- [Locking Strategy](#locking-strategy)
- [Idempotency Points](#idempotency-points)
- [Invariants Under Concurrency](#invariants-under-concurrency)
- [Race Scenarios and Expected Outcomes](#race-scenarios-and-expected-outcomes)
- [Conflict Handling](#conflict-handling)
- [Test Strategy](#test-strategy)
- [Roadmap / Production Considerations](#roadmap--production-considerations)

## Consistency Goals

The core data-correctness goals for the authorise/capture/reverse flow in `auth-service` are:

- **No double spend**: two concurrent requests must never both successfully reserve, capture, or
  reverse funds against the same account for what is logically the same operation.
- **No duplicate capture/reverse**: a capture or reverse must apply at most once per authorisation,
  even under client retries or concurrent requests racing on the same authorisation id.
- **Deterministic replay**: a retried request (same idempotency key, same payload) must return the
  original result rather than re-executing the side effect.
- **No lost updates**: concurrent writers to the same `Account`/`Authorisation` row must never
  silently overwrite each other's changes.

These goals are implemented via a combination of request-level idempotency, optimistic locking, and
DB uniqueness constraints — see [ADR 0005: Idempotency and Concurrency Rules](../decisions/0005-idempotency-and-concurrency.md)
and [ADR 0010: Database Concurrency Approach](../decisions/0010-database-concurrency-approach.md)
for the full design rationale.

## Concurrency Model per Flow

| Flow | Mutates | Concurrency guard |
|------|---------|--------------------|
| **Authorise** | `Account.availableBalance` → `reservedBalance` (on approval); persists an `AuthorisationEvent` | `AccountEntity.version` (optimistic) guards the balance mutation; unique index on `authorisation_event(account_id, event_type, idempotency_key)` guards duplicate authorise attempts with the same key |
| **Capture** | `Account.reservedBalance` (debited, final); `Authorisation.status` `AUTHORISED → CAPTURED` | Domain state check (must currently be `AUTHORISED`) runs before mutation in `AuthorisationTransactionalExecutorImpl`; `AccountEntity.version` guards the balance mutation; unique index guards duplicate capture attempts with the same key |
| **Reverse** | `Account.reservedBalance` (released back to `availableBalance`); `Authorisation.status` `AUTHORISED → REVERSED` | Same pattern as capture: state check before mutation, `AccountEntity.version` on the balance mutation, unique index on the idempotency tuple |

All three flows run inside a single DB transaction per request: the account balance change, the
authorisation/authorisation-event write, and the outbox row insert commit atomically together (see
[`outbox-pattern.md`](../eventing/outbox-pattern.md#transaction-boundary)).

## Isolation Level

- Both `auth-service` and `ledger-service` run against PostgreSQL with its default **READ COMMITTED**
  isolation level — no `@Transactional(isolation = ...)` override is configured for the
  authorise/capture/reverse transactions.
- `ledger-service`'s Kafka consumer additionally sets `isolation.level: read_committed` at the
  consumer-client level (`ledger-service/src/main/resources/application.yml`), so it only ever reads
  Kafka records from committed (non-aborted) producer transactions — a separate, Kafka-specific
  concept from the Postgres isolation level above.
- READ COMMITTED is sufficient here because correctness under concurrent writers is enforced
  explicitly at the application/schema level (optimistic locking + unique constraints), not by
  relying on a stricter DB isolation level to serialize conflicting transactions — see
  [ADR 0010](../decisions/0010-database-concurrency-approach.md) "Alternatives Considered" for why
  `SERIALIZABLE` was evaluated and not adopted.

## Locking Strategy

**Optimistic versioning only** — no pessimistic locking (`SELECT ... FOR UPDATE` /
`@Lock(LockModeType.PESSIMISTIC_WRITE)`) is used anywhere in the codebase today. This is a
deliberate choice given the current contention profile (short-lived, low-frequency-per-row) — see
[ADR 0010](../decisions/0010-database-concurrency-approach.md).

- `@Version` columns exist on:
    - `AccountEntity.version` — guards `availableBalance`/`reservedBalance` mutations.
    - `AuthorisationEntity.version` — guards authorisation state transitions.
    - `OutboxEventEntity.version` — guards the outbox publish/claim lifecycle (see
      [`outbox-pattern.md`](../eventing/outbox-pattern.md)).
- The write pattern used consistently across all three entities: **read current state → attempt
  write → on `ObjectOptimisticLockingFailureException`, catch it and resolve deterministically**
  (either re-read and replay a winning equivalent request, or surface a conflict).
- In `auth-service`, `AuthorisationTransactionalExecutorImpl` catches
  `ObjectOptimisticLockingFailureException` around the balance-mutating `account.reserve(...)` call
  and translates it to `AccountConcurrencyConflictException` — this covers two *different*,
  legitimately-keyed requests racing on the same account's version, as opposed to the same
  idempotency key racing with itself (see [Idempotency Points](#idempotency-points) below).

## Idempotency Points

Three distinct idempotency/dedup points exist across the request→event pipeline:

1. **API request idempotency key** (`auth-service`)
     - Required in the request body for `POST /authorisations`, `POST /authorisations/{id}/captures`,
       and `POST /authorisations/{id}/reversals` (and `POST /fraud/check` on `fraud-service`).
     - Persisted uniqueness boundary: `uq_authorisation_event_account_eventtype_idempotency` on
       `authorisation_event(account_id, event_type, idempotency_key)`.
     - Accelerated by a Redis response cache (`IdempotencyService`), keyed
       `{operationType}:idempotency:{scopeId}:{idempotencyKey}`, 24h TTL
       (`idempotency.ttl-hours`) — see [ADR 0007](../decisions/0007-idempotency-store-and-key-policy.md).
     - Same key + same payload → replay stored response. Same key + different payload →
       `409 IDEMPOTENCY_CONFLICT`.

2. **Outbox producer dedupe key** (`auth-service` → Kafka)
     - Each `outbox_event` row has a unique `event_id`, sent as a Kafka record header on publish.
     - A unique DB index on `(aggregate_id, event_type, idempotency_key)` prevents a duplicate
       outbox row from ever being created for the same logical domain event.
     - The Kafka producer additionally runs with `enable.idempotence: true` and `acks: all`,
       preventing the producer itself from creating duplicate broker-side writes on retry — see
       [`outbox-pattern.md`](../eventing/outbox-pattern.md#deduplication).

3. **Consumer-side dedupe key** (`ledger-service`)
     - Every consumed event's `event_id` is inserted into a `processed_event` table via
       `INSERT ... ON CONFLICT DO NOTHING`. The first delivery projects the event; any redelivery
       of the same `event_id` is a no-op.
     - Combined with manual offset commits (`enable-auto-commit: false`, `ack-mode: record`), this
       gives effectively-once ledger projections on top of Kafka's at-least-once delivery — see
       [`kafka-topics.md`](../eventing/kafka-topics.md#consumer-contract).

## Invariants Under Concurrency

- **Available balance never goes negative**: enforced in the `Account` domain object's
  `reserve(amount, currencyCode)` method
  (`auth-service/src/main/java/org/example/auth/account/domain/Account.java`) — it compares
  `availableBalance` against the requested amount and throws `InsufficientFundException` (mapped
  to `400 INSUFFICIENT_FUNDS`) before any mutation occurs if the balance would go negative. The
  account row is left untouched (only a `DECLINED` authorisation is persisted) when this fires.
- **Captured amount never exceeds authorised amount**: enforced structurally rather than via an
  explicit less-than-or-equal check, because the platform only supports **full capture** of the
  full authorised amount (no partial capture — see
  [ADR 0002: Full Capture and Full Reverse Only](../decisions/0002-full-capture-only.md)). The
  `Account.capture(amount, currencyCode)` method also independently guards against
  `reservedBalance` going negative (throws `InsufficientFundException` if the requested amount
  exceeds what's currently reserved), and the authorisation state machine only allows a capture
  from the `AUTHORISED` state, so a second capture attempt with a different idempotency key is
  rejected with `409 INVALID_AUTHORISATION_STATE` before ever reaching the balance mutation.
- The same two guards (state-check-before-mutation + balance non-negativity check inside the
  domain object) apply symmetrically to `reverse`.

## Race Scenarios and Expected Outcomes

| Scenario | Outcome |
|----------|---------|
| **Double authorise, same idempotency key** | Both requests target the same `(account_id, event_type, idempotency_key)` tuple. One commits first; the loser hits the unique constraint, is recovered via `ConcurrentIdempotencyRaceException`, re-reads the committed event, and replays the winner's response with `200 OK`. No double reservation occurs. |
| **Double authorise, different idempotency keys, same account** | Both are legitimate distinct requests racing on `AccountEntity.version`. The loser's `account.reserve(...)` throws `ObjectOptimisticLockingFailureException`, wrapped as `AccountConcurrencyConflictException` → `409 Conflict` with a retry hint. The client is expected to retry the request (it will succeed once the winner's transaction has committed and the account version has advanced), assuming sufficient available balance remains. |
| **Concurrent capture (two capture requests on the same authorisation, different idempotency keys)** | The first to commit transitions `AUTHORISED → CAPTURED`. The second either loses on `AccountEntity.version` (`409` retry) or, if it reads after the first commit, fails the pre-mutation state check and receives `409 INVALID_AUTHORISATION_STATE` (`AuthorisationIllegalStateException`) since the authorisation is no longer `AUTHORISED`. Either way, funds are captured at most once. |
| **Capture + reverse race (concurrent capture and reverse requests on the same authorisation)** | Only one of `AUTHORISED → CAPTURED` or `AUTHORISED → REVERSED` can win, since both require the authorisation to be in the `AUTHORISED` state. The loser's state-check fails (state has already transitioned) and it receives `409 INVALID_AUTHORISATION_STATE`. No scenario results in both a capture and a reverse being applied to the same authorisation. |

Retrying the *same* idempotency key against an authorisation that has already completed a terminal
transition (e.g. retrying the exact request that succeeded) is **not** treated as an invalid-state
error — it is a safe replay. Only a *different* key against an already-terminal authorisation hits
the invalid-state conflict path. See
[`failure-scenarios.md` § 5](../flows/failure-scenarios.md#5-invalid-state-transition-business-rule-violation)
for the full walkthrough.

## Conflict Handling

| Exception | HTTP status | Error code | Client guidance |
|-----------|-------------|------------|-------------------|
| `IdempotencyConflictException` | 409 | `IDEMPOTENCY_CONFLICT` | Do not retry as-is — the same key was reused with a different payload; use a new key for a genuinely new request. |
| `AuthorisationIllegalStateException` | 409 | `INVALID_AUTHORISATION_STATE` | Do not retry — the authorisation has already completed a different terminal transition. |
| `AccountConcurrencyConflictException` | 409 | `ACCOUNT_CONCURRENCY_CONFLICT` | **Retry** — this is benign infra-level contention between two legitimate requests; the operation should succeed on retry once the winning transaction has committed. |
| `InsufficientFundException` | 400 | `INSUFFICIENT_FUNDS` | Do not retry as-is — the account does not have enough available/reserved balance for the requested operation. |
| `AccountNotFoundException` / `AuthorisationNotFoundException` | 404 | `ACCOUNT_NOT_FOUND` / `AUTHORISATION_NOT_FOUND` | Do not retry — the referenced resource does not exist. |

All responses are RFC 7807 `ProblemDetail` bodies (`AuthExceptionHandler`,
`auth-service/src/main/java/org/example/auth/common/exception/AuthExceptionHandler.java`), each
carrying a stable `errorCode` field for programmatic handling in addition to the HTTP status.

## Test Strategy

- **`load-tests/generate-concurrency-conflicts.sh`** fires `N` concurrent `POST /authorisations`
  requests sharing the *same* idempotency key and an amount guaranteed to exceed the account's
  available balance (so the account row itself is never mutated, isolating the
  `idempotency_race` conflict type). It asserts all `N` requests receive an identical `200 OK`
  `DECLINED` response body, and verifies conflict counts via
  `auth_concurrency_conflict_total{operation,type}` (Prometheus).
- **`load-tests/generate-traffic.sh`** includes a dedicated "concurrency conflicts" traffic phase
  that exercises the same script as part of a broader load-generation run, to surface
  `auth_concurrency_conflict_total{type="optimistic_lock"}` under realistic mixed traffic.
- Assertions used across these scripts: HTTP status code equality across all concurrent
  responses, response-body equality for the idempotency-race case, and post-run inspection of
  `auth_concurrency_conflict_total{operation,type}` via
  `curl .../actuator/prometheus | grep auth_concurrency_conflict_total`.
- See [`docs/testing/load-testing.md`](../testing/load-testing.md) for the full test procedure and
  expected metric deltas.

## Roadmap / Production Considerations

The current concurrency model is scoped for MVP contention levels. Recommended production
follow-ups are tracked in [`docs/roadmap.md`](../roadmap.md):

- A side-by-side pessimistic-locking implementation (`SELECT ... FOR UPDATE` /
  `@Lock(LockModeType.PESSIMISTIC_WRITE)`) for reserve/capture/reverse, to compare throughput vs.
  contention trade-offs against the current optimistic-locking approach under the existing
  `load-tests/generate-concurrency-conflicts.sh` scenario (see
  [Next Steps item 5](../roadmap.md#5-next-steps-prioritized)).
- Extending idempotency coverage to `POST /accounts` and `POST /accounts/{id}/deposits`, which are
  not idempotent today (see [Next Steps item 6](../roadmap.md#5-next-steps-prioritized)).
- Expanded failure-path/concurrency test automation (timeouts, duplicate events, DLT replay) — see
  [Next Steps item 7](../roadmap.md#5-next-steps-prioritized).

