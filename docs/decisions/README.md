# Architecture Decision Records (ADR) Index

This folder tracks architecture decisions for the payment platform (`auth-service`, `fraud-service`,
`ledger-service`, `shared-spring-lib`). Each ADR is numbered sequentially and is immutable once accepted — if a
decision changes, add a new ADR and mark the old one as *Superseded*, linking to the replacement.

| # | Title | Status | Date | Summary |
|---|-------|--------|------|---------|
| [0001](./0001-use-kafka-outbox.md) | Use Kafka + Transactional Outbox | Accepted | 2026-07-31 | `auth-service` writes domain state and an outbox row in one DB transaction, then publishes to Kafka asynchronously, avoiding dual-write inconsistency. |
| [0002](./0002-full-capture-only.md) | Support Full Capture and Full Reverse Only (MVP) | Accepted | 2026-07-31 | MVP scope limits authorisation operations to full capture and full reverse; partial capture/reverse and refunds are out of scope. |
| [0003](./0003-ledger-raw-and-normalized-events.md) | Store Both Raw and Normalized Ledger Events | Accepted | 2026-07-31 | `ledger-service` persists raw Kafka payloads (`ledger_event_log`) for audit/replay and normalized rows (`ledger_entry`) for querying, plus `processed_event` for dedup. |
| [0004](./0004-use-event-and-dlt-topics.md) | Use Separate Main Event and DLT Topics | Accepted | 2026-07-31 | Non-transient consumer failures are routed to a dead-letter topic (`auth.events.ledger.dlt`) after retry, isolating poison messages from healthy traffic. |
| [0005](./0005-idempotency-and-concurrency.md) | Idempotency and Concurrency Rules for Authorisation Flows | Accepted | 2026-08-06 | Defines correctness rules: request-level idempotency in `auth-service`, event-level dedup in `ledger-service`, optimistic-locking as final concurrency guard, key scope, replay/conflict semantics. See also [0007](./0007-idempotency-store-and-key-policy.md) for storage/policy. |
| [0006](./0006-redis-sliding-window-rate-limit.md) | Use Redis for Sliding-Window Rate Limiting (Fraud Service) | Accepted | 2026-08-13 | `fraud-service` uses a Redis sorted-set + Lua script sliding window to enforce velocity-based fraud rules atomically across instances. |
| [0007](./0007-idempotency-store-and-key-policy.md) | Idempotency Store Strategy and Key Policy (Auth/Fraud) | Accepted | 2026-08-13 | Implementation policy for idempotency: Redis-cached responses in `auth-service` (24h TTL), DB unique-constraint claim rows in `fraud-service`, key scope, and replay/conflict handling. Builds on correctness constraints from [0005](./0005-idempotency-and-concurrency.md). |
| [0008](./0008-resilience4j-circuit-breaker-policy.md) | Use Resilience4j for Circuit Breaker / Time Limiter (Fraud Gateway) | Accepted | 2026-08-13 | `auth-service` -> `fraud-service` calls are wrapped with Resilience4j `@TimeLimiter` (250ms) and `@CircuitBreaker`, falling back to an `unavailable` fraud decision instead of propagating failures. |
| [0009](./0009-shared-spring-lib-for-cross-cutting.md) | Shared Spring Library for Cross-Cutting Concerns | Accepted | 2026-08-13 | `shared-spring-lib` centralizes the RFC 7807 error model, correlation-id propagation (filter/interceptor), and idempotency request hashing shared by all services. |
| [0010](./0010-database-concurrency-approach.md) | Database Concurrency Approach (Optimistic vs Pessimistic Locking) | Accepted | 2026-08-13 | `auth-service` entities (`AccountEntity`, `AuthorisationEntity`, `OutboxEventEntity`) use JPA `@Version` optimistic locking exclusively; no pessimistic locking is used today. |

## Conventions

- **Status** is one of: `Proposed`, `Accepted`, `Superseded` (with a link to the ADR that supersedes it), or
  `Rejected`.
- New ADRs get the next sequential number, a short kebab-case title, and should link back to related ADRs/docs in a
  `## Related` section.
- Prefer adding a new ADR over silently editing an accepted one; small clarifications/cross-links are fine to add
  in place (as done between 0005 and 0007).

