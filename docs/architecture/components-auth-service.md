# Components — auth-service (C4 Level 3)

## Table of Contents

- [Component diagram](#component-diagram)
- [Sequence: authorise happy path (mini)](#sequence-authorise-happy-path-mini)
- [Responsibility table](#responsibility-table)
- [Boundary notes](#boundary-notes)
    - [Transaction boundary](#transaction-boundary)
    - [Idempotency / concurrency points](#idempotency--concurrency-points)
    - [External calls](#external-calls)

This zooms into the `auth-service` container from [Containers](./containers.md) and shows its key internal
building blocks for the authorise/capture/reverse write path. Scope: **auth-service only** — `fraud-service` and
`ledger-service` appear only as external dependencies (an outbound client call and, indirectly, a Kafka
consumer), not as internals of this diagram.

## Component diagram

Components are grouped into the six categories that make up `auth-service`: **controllers**, **application
services** (orchestrator + idempotency + the transactional executor), **domain** (entities), **repositories**,
**outbox**, and **outbound clients**.

Source: [`components-auth-service.mmd`](./components-auth-service.mmd) — open in a Mermaid-compatible
viewer (the Mermaid VS Code/IntelliJ plugin, or [mermaid.live](https://mermaid.live)) to render it.

## Sequence: authorise happy path (mini)

Source: [`authorise-happy-path.mmd`](./authorise-happy-path.mmd) — open in a Mermaid-compatible
viewer (the Mermaid VS Code/IntelliJ plugin, or [mermaid.live](https://mermaid.live)) to render it.

## Responsibility table

| Component | Category | Responsibility |
|---|---|---|
| `AuthorisationController` / `AccountController` | Controller | Entry point. Request validation (bean validation), delegates to the application service, maps results to HTTP responses. No business logic. |
| `AuthExceptionHandler` | Controller | Maps domain exceptions (`IdempotencyConflictException`, `AccountConcurrencyConflictException`, `AuthorisationIllegalStateException`, `AccountNotFoundException`, validation errors) to RFC 7807 `ProblemDetail` responses with stable HTTP status codes. |
| `AuthorisationServiceImpl` | Application service | Orchestrates a single use case end-to-end: normalizes/validates input, checks the idempotency cache, performs a best-effort DB idempotency pre-check, calls the fraud client, delegates the mutation to the transactional executor, and resolves any concurrency races that surface. |
| `IdempotencyService` | Application service | Redis-backed cache of previously computed responses, keyed `{operationType}:idempotency:{scopeId}:{idempotencyKey}`, configurable TTL (default 24h). Provides `get`/`store`; does not itself decide conflict vs. replay. See [ADR 0007](../decisions/0007-idempotency-store-and-key-policy.md). |
| `AuthorisationTransactionalExecutorImpl` | Application service (transaction boundary) | Everything inside one `@Transactional` method: the authoritative idempotency check-and-insert, the account state mutation (`reserve`/`capture`/`reverse`), building domain rows, and enqueuing the outbox event. See [ADR 0005](../decisions/0005-idempotency-and-concurrency.md) / [ADR 0010](../decisions/0010-database-concurrency-approach.md). |
| `FraudOrchestrator` / `ResilientFraudGateway` | Outbound client | Calls `fraud-service` over HTTP, wrapped in a Resilience4j `@CircuitBreaker` + `@TimeLimiter` (250ms); falls back to an `unavailable` decision (tagged `TIMEOUT`/`CIRCUIT_OPEN`/etc.) instead of propagating failures. See [ADR 0008](../decisions/0008-resilience4j-circuit-breaker-policy.md). |
| `AccountEntity` / `AuthorisationEntity` / `AuthorisationEventEntity` | Domain | JPA entities carrying the domain state and rules (`AccountEntity.reserve/capture/reverse`, `@Version` optimistic-locking columns, status enums). The transactional executor mutates these directly; nothing else does. |
| `AccountRepository` / `AuthorisationRepository` / `AuthorisationEventRepository` | Repository | Spring Data JPA repositories for current-state (`account`, `authorisation`) and append-only audit (`authorisation_event`) tables. `AuthorisationEventRepository` backs the idempotency uniqueness constraint (`account_id + event_type + idempotency_key`). |
| `OutboxEventServiceImpl` | Outbox | Called from *inside* the same transaction as the domain mutation: builds the event payload map and inserts an `outbox_event` row (status `NEW`) via `OutboxEventRepository`. Guarantees the event is never lost or published without the corresponding state change ([ADR 0001](../decisions/0001-use-kafka-outbox.md)). |
| `OutboxEventRepository` | Outbox | Spring Data JPA repository for the `outbox_event` table; used both inside the write transaction (insert) and by the background poller (claim/mark-outcome). |
| `OutboxScheduler` / `OutboxKafkaPublisher` | Outbox | Separate async path, own transaction(s). Polls `outbox_event` on a fixed delay, claims rows with a lease (`PUBLISHING` + `claim_until`), publishes to Kafka with `eventId`/`correlationId`/`traceparent` headers, and marks `PUBLISHED`/`FAILED`/retries based on the outcome. |

## Boundary notes

### Transaction boundary

- **Boundary #1 (request-scoped, short):** `AuthorisationTransactionalExecutorImpl` — one DB transaction per
  authorise/capture/reverse attempt, covering the idempotency insert guard, the account/authorisation/event
  writes (domain entities), and the outbox row insert. Commits or rolls back as a unit.
- **Boundary #2 (background, decoupled):** the outbox publish loop (`OutboxScheduler`) — its own transaction(s)
  for claim/publish/mark-outcome, running on a timer independent of any HTTP request.
- The Redis idempotency cache read/write in `AuthorisationServiceImpl` sits **outside** both transactions — a
  best-effort fast path, not a source of truth (the DB unique constraint is authoritative).

### Idempotency / concurrency points

1. **Cache check (fast path, no DB hit):** `AuthorisationServiceImpl` → `IdempotencyService.get(...)`. A hit
   returns the previously computed response immediately.
2. **Best-effort DB pre-check (before opening the write transaction):** `AuthorisationServiceImpl` queries
   `AuthorisationRepository` for an existing row matching the scope/key — catches most replays/conflicts cheaply
   without paying for a transactional write attempt.
3. **Authoritative check-and-insert (inside the transaction):** `AuthorisationTransactionalExecutorImpl` repeats
   the lookup and relies on the DB unique constraint to be the final word — a concurrent request that raced past
   step 2 fails here with a constraint violation, surfaced as `ConcurrentIdempotencyRaceException`.
4. **Optimistic-locking check (inside the transaction):** the `@Version` column on `AccountEntity` detects a
   conflicting concurrent mutation of the *same account* under a *different* idempotency key, surfaced as
   `AccountConcurrencyConflictException`.
5. **Race recovery (back in the orchestrator):** on either exception from step 3/4, `AuthorisationServiceImpl`
   re-reads persisted state to decide whether to replay the winner's response or return a `409 Conflict`.

### External calls

- **Outbound, synchronous:** `FraudOrchestrator` / `ResilientFraudGateway` → `fraud-service` (`POST
  /fraud/check`), circuit-breaker + 250ms time-limiter protected, with an `unavailable`-decision fallback. This is
  the only synchronous outbound call auth-service makes.
- **Outbound, asynchronous:** `OutboxKafkaPublisher` → Kafka (`auth.events`), from the background scheduler, not
  the request thread.
- **Outbound, cache:** `IdempotencyService` → Redis (`get`/`store`), on the request thread but outside the DB
  transaction.
- **Inbound:** HTTP only, via `AuthorisationController` / `AccountController`. No other service calls into
  auth-service — it is the sole entry point that mutates payment state.



