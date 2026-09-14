# ADR 0008: Use Resilience4j for Circuit Breaker / Time Limiter (Fraud Gateway)

- Status: Accepted
- Date: 2026-08-13

## Context

`auth-service` calls `fraud-service` synchronously (over HTTP) as part of the authorise flow, via
`ResilientFraudGateway`. A slow or unavailable `fraud-service` should not be allowed to:

- exhaust `auth-service` threads/connections while waiting on fraud responses
- cascade latency/failures back to callers of `auth-service`
- keep retrying a downstream that is already known to be failing

A bounded-timeout, fail-fast, self-healing call pattern is needed for this dependency.

## Decision

Use Resilience4j (`resilience4j-spring-boot4`, `resilience4j-micrometer`) in `auth-service` to wrap the fraud call:

- `@TimeLimiter(name = "fraudService")` bounds the async fraud call: `timeoutDuration: 250ms`,
  `cancelRunningFuture: true`
- `@CircuitBreaker(name = "fraudService", fallbackMethod = "fallback")` protects against sustained failure/slowness:
    - `slidingWindowSize: 10`
    - `failureRateThreshold: 50` (%)
    - `slowCallRateThreshold: 50` (%), `slowCallDurationThreshold: 100ms`
    - `waitDurationInOpenState: 15s`
    - `permittedNumberOfCallsInHalfOpenState: 3`
- On timeout, circuit-open, or deserialization failure, `fallback(...)` returns `FraudDecision.unavailable(reason)`
  instead of propagating the exception, tagging the reason (`TIMEOUT`, `CIRCUIT_OPEN`, `DESERIALIZATION`, etc.) for
  observability
- The fraud call itself runs on a dedicated MDC-propagating executor (`fraudMdcExecutor`) so correlation/tracing
  context survives the async boundary

No `@Retry` decorator is currently applied to the fraud call (see Alternatives/Trade-offs).

## Consequences

Positive:
- bounded worst-case latency contribution from the fraud dependency (250ms) instead of unbounded blocking
- circuit breaker prevents hammering a struggling `fraud-service` and lets it recover
- callers get a deterministic `unavailable` decision (with reason) rather than a hard failure, so upstream policy
  (e.g. decline-open vs allow-open) is a single decision point
- metrics/observability come largely for free via `resilience4j-micrometer`

Trade-offs:
- an `unavailable` fraud decision still requires a policy decision elsewhere in the authorise flow (fail-open vs
  fail-closed) — this ADR only covers the resilience mechanics, not that business policy
- circuit breaker state is per `auth-service` instance (no shared state across instances), so different instances
  can independently be open/closed for the same downstream
- no `@Retry` is currently configured for the fraud call — a transient blip is treated the same as a timeout/failure
  rather than retried once before failing

## Alternatives Considered

- add `@Retry` on top of `@CircuitBreaker`/`@TimeLimiter` (deferred, not rejected outright — see
  `AccountConcurrencyConflictException` docs, which note a `@Retry` decorator mirroring `ResilientFraudGateway`
  could resolve some race-loss retries; not yet adopted for the fraud gateway to avoid amplifying load on an
  already-slow downstream during partial outages)
- plain HTTP client timeout only, no circuit breaker (rejected: does not prevent repeated calls to a downstream
  that is already failing, no fail-fast behavior once failure is sustained)
- hand-rolled circuit breaker/timeout logic (rejected: reinvents a well-tested library, worse observability)

## Related

- `docs/decisions/0007-idempotency-store-and-key-policy.md`
- `auth-service/src/main/java/org/example/auth/fraud/ResilientFraudGateway.java`
- `auth-service/src/main/java/org/example/auth/common/exception/AccountConcurrencyConflictException.java`
- `auth-service/src/main/resources/application.yml` (resilience4j config block)

