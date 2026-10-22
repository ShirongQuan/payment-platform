# ADR 0008: Use Resilience4j for Circuit Breaker / Time Limiter (Fraud Gateway)

- Status: Accepted (amended 2026-10-22, see [Update](#update-2026-10-22-timelimiter-250ms--500ms))
- Date: 2026-08-13

## Context

`auth-service` calls `fraud-service` synchronously (over HTTP) as part of the authorise flow, via
`ResilientFraudGateway`. A slow or unavailable `fraud-service` should not be allowed to:

- exhaust `auth-service` threads/connections while waiting on fraud responses
- cascade latency/failures back to callers of `auth-service`
- keep retrying a downstream that is already known to be failing

A bounded-latency, failure-isolating call pattern is needed for this synchronous service dependency.

## Decision

Use Resilience4j (`resilience4j-spring-boot4`, `resilience4j-micrometer`) in `auth-service` to wrap the fraud call:

- `@TimeLimiter(name = "fraudService")` bounds the async fraud call: `timeoutDuration: 500ms`,
  `cancelRunningFuture: true` (see [Update](#update-2026-10-22-timelimiter-250ms--500ms) — originally `250ms`)
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
- bounded worst-case latency contribution from the fraud dependency (500ms, see
  [Update](#update-2026-10-22-timelimiter-250ms--500ms) — originally 250ms) instead of unbounded blocking
- circuit breaker prevents hammering a struggling `fraud-service` and lets it recover
- callers get a deterministic `unavailable` decision (with reason) rather than a hard failure, so upstream policy
  (e.g. decline-open vs allow-open) is a single decision point
- metrics/observability come largely for free via `resilience4j-micrometer`

Trade-offs:
- an `unavailable` fraud decision still requires a policy decision elsewhere in the authorise flow (fail-open vs
  fail-closed) — this ADR only covers the resilience mechanics, not that business policy
- circuit breaker state is per `auth-service` instance (no shared state across instances), so different instances
  can independently be open/closed for the same downstream
- the fraud call deliberately has no automatic retry decorator; this avoids multiplying synchronous calls against a downstream service that is already slow or unavailable. Retry policy can be revisited separately if measured failure patterns justify it.

## Alternatives Considered

- add `@Retry` on top of `@CircuitBreaker`/`@TimeLimiter` (deferred: automatic retries can amplify load and latency against a degraded synchronous dependency; the policy can be revisited using observed failure characteristics — see [roadmap: Production Considerations](../roadmap.md#6-production-considerations))
- plain HTTP client timeout only, no circuit breaker (rejected: does not prevent repeated calls to a downstream
  that is already failing, no fail-fast behavior once failure is sustained)
- hand-rolled circuit breaker/timeout logic (rejected: reinvents a well-tested library, worse observability)

## Related

- [ADR 0007: Idempotency Store Strategy and Key Policy (Auth/Fraud)](./0007-idempotency-store-and-key-policy.md)
- `auth-service/src/main/java/org/example/auth/fraud/ResilientFraudGateway.java`
- `auth-service/src/main/java/org/example/auth/common/exception/AccountConcurrencyConflictException.java`
- `auth-service/src/main/resources/application.yml` (resilience4j config block)

## Update (2026-10-22): TimeLimiter 250ms → 500ms

### Context

`fraud.http.connect-timeout` / `fraud.http.read-timeout` were originally `100ms` / `150ms` (worst-case socket time
`250ms`), matched almost exactly to the `@TimeLimiter` budget of `250ms`. With virtually no slack between the two
mechanisms, normal jitter (JVM/thread-pool scheduling onto `fraudMdcExecutor`, GC pauses, response deserialization
time, or small network variance in local/docker-compose setups) could cause the Resilience4j timer to fire *before*
the underlying HTTP client's own timeout. This made the two timeout mechanisms race unpredictably under load, so the
fallback reason surfaced inconsistently as either `fraud_fallback:TIMEOUT` (Resilience4j's own `TimeoutException`) or
a raw `RestClientException` / `SocketTimeoutException` from the HTTP client, depending on timing luck — undermining
the goal of a single, deterministic fallback reason for observability (see Decision above).

### Decision

- `fraud.http.connect-timeout` / `fraud.http.read-timeout`: `100ms` / `150ms` → **`150ms` / `150ms`**
- `@TimeLimiter(name = "fraudService")` `timeoutDuration`: `250ms` → **`500ms`**

Math check:

- `connect-timeout (150ms) + read-timeout (150ms)` = `300ms` worst-case socket time
- `@TimeLimiter` budget = `500ms`
- `300ms` comfortably fits inside the `500ms` caller budget, leaving `~200ms` of slack for JVM/thread-pool scheduling
  jitter, response deserialization after bytes are read, and network variance — so the HTTP client should basically
  always win the race, keeping the fallback reason consistently `fraud_fallback:TIMEOUT`

### Consequences

- more consistent, correctly-attributed fallback reasons (`TIMEOUT` via Resilience4j, rather than an
  occasionally-surfaced raw HTTP client timeout exception), which is the more valuable trait for a demo/learning
  platform focused on illustrating the resilience patterns clearly
- trade-off: doubles the worst-case latency contribution fraud-service can add to the `/authorisations` request path
  (250ms → 500ms) before `auth-service` gives up. `250ms` was originally chosen deliberately to bound worst-case
  latency tightly (see Decision above) — this is a conscious trade of tighter tail latency for more reliable timeout
  classification, acceptable here given the platform's demo/learning focus
- no change to the circuit breaker's `slowCallDurationThreshold` (`100ms`) — calls are still classified as slow at
  the same threshold; only the hard timeout budget and underlying socket timeouts moved

### Related (update)

- `auth-service/src/main/resources/application.yml` (`fraud.http.connect-timeout` / `read-timeout`,
  `resilience4j.timelimiter.instances.fraudService.timeoutDuration`)

