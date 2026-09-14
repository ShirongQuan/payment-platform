# ADR 0009: Shared Spring Library for Cross-Cutting Concerns

- Status: Accepted
- Date: 2026-08-13

## Context

`auth-service`, `fraud-service`, and `ledger-service` each need the same cross-cutting behavior:

- a consistent error/response model for API failures
- propagation of a correlation/trace identifier across HTTP calls and into logs
- a shared way to derive stable idempotency keys/hashes from request content

Implementing these independently per service risks drift (different header names, different error shapes, subtly
different hashing) and duplicated maintenance effort.

## Decision

Introduce `shared-spring-lib`, a common library included by all three services, that owns:

- **Error model** (`org.example.shared.error`): `ProblemDetails` — a shared helper for building RFC 7807
  `ProblemDetail` responses, so all services return a consistent error shape to callers.
- **Correlation/tracing** (`org.example.shared.correlation`):
    - `CorrelationIdConstants` — shared header name and MDC key
    - `CorrelationIdFilter` — inbound servlet filter that reads/generates the correlation id and puts it in MDC for
      logging
    - `CorrelationIdInterceptor` — outbound HTTP client interceptor that propagates the correlation id on
      service-to-service calls
    - `CorrelationIdResolver`, `CorrelationIdAutoConfiguration` — resolution logic and Spring Boot auto-configuration
      so services pick this up with minimal wiring
- **Idempotency utility** (`org.example.shared.idempotency`): `RequestHashing` — canonical request field joining and
  SHA-256 hashing, used where a stable hash of request content is needed (for example, detecting a changed payload
  under the same idempotency key).
- **Validation** (`org.example.shared.currency`): `ValidCurrencyCode` / `CurrencyCodeValidator` — shared bean
  validation for currency code fields.

Each service depends on `shared-spring-lib` as a regular Maven module dependency; auto-configuration wires the
filter/interceptor beans without per-service boilerplate.

## Consequences

Positive:
- one correlation id header/MDC convention across all services, so traces/logs can be joined end-to-end
  (see `docs/architecture/system-overview.md`, `docs/architecture/service-interaction.mmd`)
- consistent error response shape simplifies client-side and gateway-side error handling
- shared hashing logic avoids subtly different idempotency-conflict detection per service
- changes to cross-cutting behavior (e.g. adding a new MDC field) are made once and consumed everywhere

Trade-offs:
- introduces a shared build/versioning dependency between otherwise independently deployable services — a breaking
  change in `shared-spring-lib` can require coordinated upgrades across services
- shared library needs its own testing/versioning discipline; it is effectively a mini-platform now
- risk of the library becoming a dumping ground for anything "shared" if scope isn't kept to genuinely
  cross-cutting concerns

## Alternatives Considered

- duplicate the correlation filter/error model per service (rejected: drift risk already observed as a motivation;
  higher long-term maintenance cost)
- push cross-cutting concerns into infrastructure (e.g. API gateway/service mesh header injection) instead of a
  library (deferred: no gateway/mesh currently in the stack; library approach is simpler for the current
  three-service scope)
- separate libraries per concern (error model, correlation, idempotency) instead of one shared module (rejected for
  now: adds packaging/versioning overhead disproportionate to current size; package-level separation inside one
  module gives similar clarity)

## Related

- `docs/decisions/0007-idempotency-store-and-key-policy.md`
- `docs/architecture/service-interaction.mmd`
- `shared-spring-lib/src/main/java/org/example/shared/error/ProblemDetails.java`
- `shared-spring-lib/src/main/java/org/example/shared/correlation/`
- `shared-spring-lib/src/main/java/org/example/shared/idempotency/RequestHashing.java`

