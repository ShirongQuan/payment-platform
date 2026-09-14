# ADR 0010: Database Concurrency Approach (Optimistic vs Pessimistic Locking)

- Status: Accepted
- Date: 2026-08-13

## Context

Multiple entities across `auth-service` are subject to concurrent updates:

- `AccountEntity` — balance/available-balance mutated by authorise/capture/reverse
- `AuthorisationEntity` — state transitions (`AUTHORISED -> CAPTURED` / `AUTHORISED -> REVERSED`)
- `OutboxEventEntity` — marked published by the outbox dispatcher, potentially racing with retries

Contention is expected to be **short-lived and low-frequency per row** (a given account/authorisation is not
typically hammered by dozens of simultaneous writers), which favors optimistic concurrency over holding
row-level locks for the duration of a transaction.

## Decision

Use **optimistic locking** (JPA `@Version`) as the default and, so far, only concurrency control mechanism for
mutable entities in `auth-service`:

- `AccountEntity.version`
- `AuthorisationEntity.version`
- `OutboxEventEntity.version`

No pessimistic locking (`@Lock(LockModeType.PESSIMISTIC_WRITE)`, `SELECT ... FOR UPDATE`) is used anywhere in the
codebase today. Combined with the unique constraints from ADR 0005 (idempotency tuple uniqueness) and
`fraud-service`'s idempotency-claim insert (ADR 0007), the following race pattern is used consistently:

1. Read current state.
2. Attempt to write (insert/update), relying on `@Version`/unique constraints to detect a conflicting concurrent
   writer at commit time.
3. On conflict (`OptimisticLockingFailureException` / `DataIntegrityViolationException`), re-read persisted state
   and resolve deterministically: replay a winning equivalent request, or return a conflict/error for a genuinely
   conflicting request (see ADR 0005 section 5).

## Consequences

Positive:
- no long-held row locks — better throughput and no lock-wait/deadlock exposure under normal load
- conflict detection is cheap (a version column check) compared to holding locks across a transaction
- fits the existing idempotency/replay model: a losing writer doesn't need to block, it re-reads and resolves

Trade-offs:
- under genuinely high contention on the *same* row (e.g. many concurrent operations against one account), optimistic
  locking causes retries/aborts rather than serializing writers, which could increase client-visible conflict rates
  if traffic patterns change
- every write path needs explicit conflict-handling logic (catch + re-read + resolve); this is more application code
  than relying on the database to serialize via pessimistic locks
- optimistic locking does not, by itself, prevent lost updates on non-versioned reads elsewhere in a transaction —
  discipline is required to always go through the versioned entity for mutations

## Alternatives Considered

- pessimistic locking (`SELECT ... FOR UPDATE`) on `AccountEntity`/`AuthorisationEntity` for the duration of each
  mutating transaction (rejected for now: simpler mental model, but reduces throughput under load and introduces
  lock-wait/deadlock risk; current contention profile doesn't justify it)
- database-level serializable isolation for all authorise/capture/reverse transactions (rejected: broad
  performance cost across all transactions, not just the contended ones, and Postgres serializable adds
  retry-on-serialization-failure handling that is functionally similar to what optimistic locking already gives us)
- distributed lock manager across service instances (rejected: same reasoning as ADR 0005 — added operational
  complexity/latency not currently justified)

## When to Revisit

If load testing (see `load-tests/generate-concurrency-conflicts.sh`) shows unacceptable client-visible conflict
rates on hot accounts, consider pessimistic locking scoped narrowly to that specific write path rather than
switching the whole system's concurrency model.

## Related

- `docs/decisions/0005-idempotency-and-concurrency.md`
- `docs/decisions/0007-idempotency-store-and-key-policy.md`
- `auth-service/src/main/java/org/example/auth/account/infrastructure/AccountEntity.java`
- `auth-service/src/main/java/org/example/auth/authorisation/infrastructure/AuthorisationEntity.java`
- `auth-service/src/main/java/org/example/auth/outbox/infrastructure/OutboxEventEntity.java`
- `load-tests/generate-concurrency-conflicts.sh`

