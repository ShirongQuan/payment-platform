# Resilience Patterns

## Table of Contents

- [Dependency Failure Map](#dependency-failure-map)
- [Timeouts and Retry Policy per Dependency](#timeouts-and-retry-policy-per-dependency)
- [Circuit Breaker Policy](#circuit-breaker-policy)
- [Bulkhead / Rate Limiting / Load Shedding](#bulkhead--rate-limiting--load-shedding)
- [Outbox Retry + DLT Flow](#outbox-retry--dlt-flow)
- [Kafka Consumer Retry + DLQ Handling](#kafka-consumer-retry--dlq-handling)
- [Compensation Strategy](#compensation-strategy)
- [Degradation Modes](#degradation-modes)
- [Observability Tie-In](#observability-tie-in)
- [Operational Playbook Links](#operational-playbook-links)
- [Roadmap / Production Considerations](#roadmap--production-considerations)

## Dependency Failure Map

| Dependency | Consumed by | Failure handling implemented today |
|------------|-------------|----------------------------------------|
| **fraud-service** (HTTP, sync) | `auth-service` (authorise flow) | Resilience4j `@TimeLimiter` + `@CircuitBreaker` with a deterministic fallback (`FraudDecision.unavailable(reason)`); a fail-open policy for trusted, tiny-amount transactions (disabled by default) |
| **Kafka** | `auth-service` (producer, via outbox), `ledger-service` (consumer) | Transactional outbox with backoff/retry on the producer side; bounded retry + DLT routing on the consumer side |
| **Postgres** | All three services | Hard dependency — no degradation path; a Postgres outage fails the owning service's requests directly (see [Degradation Modes](#degradation-modes)) |
| **Redis** | `auth-service` (idempotency response cache), `fraud-service` (velocity/rate-limit rules) | Best-effort cache — a Redis outage degrades to normal DB-level execution/conflict handling in `auth-service` rather than hard-failing; `fraud-service`'s velocity rules have a hard runtime dependency on Redis (see [ADR 0006](../decisions/0006-redis-sliding-window-rate-limit.md)) |

## Timeouts and Retry Policy per Dependency

| Dependency | Timeout | Retry | Backoff | Retryable error classes |
|------------|---------|-------|---------|---------------------------|
| **fraud-service call** (`ResilientFraudGateway.checkAsync`) | `250ms` (`@TimeLimiter(timeoutDuration=250ms, cancelRunningFuture=true)`) | **None** — no `@Retry` decorator is applied on top of the circuit breaker/time limiter | n/a | n/a — a timeout, circuit-open, or deserialization failure all fall straight through to the fallback (see [Circuit Breaker Policy](#circuit-breaker-policy)) rather than being retried |
| **fraud-service HTTP client** (connection-level) | `connect-timeout: 500ms`, `read-timeout: 1000ms` (`auth-service` application.yml `fraud.http`) | n/a (bounded by the `@TimeLimiter` above at the call-site level) | n/a | n/a |
| **Kafka producer** (`auth-service` outbox publish) | `delivery.timeout.ms: 20000`, `request.timeout.ms: 8000` | `retries: 5` (Kafka client-level retry) | Kafka client default (exponential, internal to the producer) | Transient broker-level send failures; bounded to stay under the outbox claim lease (30s) so a slow/retrying send is never reclaimed and re-published concurrently |
| **Outbox publish attempts** (application-level, `OutboxScheduler`/`OutboxBackoffPolicy`) | n/a (poll-driven) | Up to 5 attempts before terminal `FAILED` | `10s` (attempt 1) → `30s` (attempt 2) → `60s` (attempt 3) → `300s` (attempt 4+) | Any publish failure increments `retry_count`; see [`outbox-pattern.md`](../eventing/outbox-pattern.md#retry-policy) |
| **Kafka consumer** (`ledger-service`, `KafkaConsumerConfig`) | n/a | **3 retries** (`FixedBackOff(2000L, 3L)`) | Fixed `2000ms` (2s) between attempts | All exceptions except `IllegalArgumentException` (malformed payload/missing headers, which go straight to the DLT since they will never succeed on retry) |

No jitter is applied anywhere today — both the outbox backoff schedule and the Kafka consumer's
fixed backoff are deterministic, non-jittered delays. See
[Roadmap](#roadmap--production-considerations) for a note on this.

## Circuit Breaker Policy

Configured for the `auth-service` → `fraud-service` call via Resilience4j
(`auth-service/src/main/resources/application.yml`, instance name `fraudService`):

| Setting | Value | Meaning |
|---------|-------|---------|
| `slidingWindowType` | `COUNT_BASED` | Evaluates the last N calls, not a time window. |
| `slidingWindowSize` | `10` | Window = last 10 calls. |
| `minimumNumberOfCalls` | `5` | At least 5 calls must complete before the failure rate is evaluated. |
| `failureRateThreshold` | `50%` | Circuit **opens** if ≥50% of the window's calls failed. |
| `slowCallRateThreshold` | `50%` | Circuit **opens** if ≥50% of calls were "slow". |
| `slowCallDurationThreshold` | `100ms` | A call counts as "slow" if it took more than 100ms. |
| `waitDurationInOpenState` | `15s` | Circuit stays **open** (fails fast, no calls attempted) for 15s. |
| `permittedNumberOfCallsInHalfOpenState` | `3` | After 15s, allow 3 trial calls in **half-open** state. |
| `automaticTransitionFromOpenToHalfOpenEnabled` | `true` | Automatically flips open → half-open after the wait duration, without needing an incoming call to trigger the transition. |

- **Open → Half-open → Closed**: if the 3 half-open trial calls succeed (and aren't slow), the
  circuit **closes** and normal traffic resumes. If they fail, the circuit re-opens for another
  `15s` wait.
- On **timeout**, **circuit-open** (`CallNotPermittedException`), or **deserialization failure**
  (`HttpMessageConversionException`), `ResilientFraudGateway.fallback(...)` returns
  `FraudDecision.unavailable(reason)` instead of propagating the exception — the reason is tagged
  (`fraud_fallback:TIMEOUT`, `fraud_fallback:CIRCUIT_OPEN`, `fraud_fallback:DESERIALIZATION`, or the
  raw exception class name) for observability.
- The fraud call runs on a dedicated MDC-propagating executor (`fraudMdcExecutor`) so
  correlation-id/tracing context survives the async boundary introduced by `@TimeLimiter`.
- See [ADR 0008: Resilience4j Circuit Breaker/Time Limiter Policy](../decisions/0008-resilience4j-circuit-breaker-policy.md)
  for the full design rationale, including why `@Retry` was deliberately not added on top (to avoid
  amplifying load on an already-degraded downstream).

## Bulkhead / Rate Limiting / Load Shedding

- **No Resilience4j Bulkhead** is used anywhere in the codebase today — thread/connection
  isolation for the fraud call relies on the `@TimeLimiter`'s bounded timeout and dedicated executor
  rather than a concurrent-call-count bulkhead.
- **No generic HTTP-level rate limiting** (e.g. per-IP or per-API-key request throttling) exists at
  the application or gateway layer — this is a deliberate, explicitly-documented MVP scope cut (see
  [Roadmap](#roadmap--production-considerations)).
- **Application-layer anti-abuse signal** (implemented, business-aware — not generic throttling):
  `fraud-service` implements Redis-backed sliding-window velocity rules via
  `VelocityServiceRedisImpl`, computed atomically with a Lua script
  (`ZADD`/`ZREMRANGEBYSCORE`/`ZCARD` in a single round trip):
    - **IP velocity**: more than 3 requests in 5 seconds from the same IP → `+40` risk score,
      reason `IP_VELOCITY_EXCEEDED`.
    - **Account velocity**: more than 3 requests in 5 seconds for the same account → `+30` risk
      score, reason `ACCOUNT_VELOCITY_EXCEEDED`.
    - These feed the fraud risk score rather than rejecting/throttling HTTP traffic directly — see
      [ADR 0006](../decisions/0006-redis-sliding-window-rate-limit.md) for why Redis sorted sets
      were chosen over in-memory counters or fixed-window counters.

## Outbox Retry + DLT Flow

Covered in full detail in [`outbox-pattern.md`](../eventing/outbox-pattern.md) — summary:

- `OutboxScheduler` polls every 10s, batches up to 10 due rows, and publishes via
  `OutboxKafkaPublisher`.
- `OutboxBackoffPolicy` retries a failed publish with increasing backoff (10s/30s/60s/300s) up to
  5 attempts before marking the row terminally `FAILED`.
- There is **no separate dead-letter topic/table for producer-side (outbox) failures** — a `FAILED`
  row remains visible in `outbox_event` for manual operator triage/replay. See
  [`outbox-pattern.md` § Runbook Links](../eventing/outbox-pattern.md#runbook-links).

## Kafka Consumer Retry + DLQ Handling

Configured in `ledger-service/src/main/java/org/example/ledger/KafkaConsumerConfig.java`:

- `DefaultErrorHandler` wraps a `DeadLetterPublishingRecoverer` with `FixedBackOff(2000L, 3L)` —
  **3 retries, fixed 2-second backoff**.
- **Non-retryable**: `IllegalArgumentException` (malformed payload / missing headers) is added via
  `addNotRetryableExceptions(...)` and routed straight to the DLT, since these are deterministic
  failures that will never succeed on retry.
- **Retryable**: all other exceptions are retried up to 3 times before the recoverer routes the
  record to the DLT.
- The `DeadLetterPublishingRecoverer` republishes the failed record to `auth.events.ledger.dlt` on
  **the same partition number** as the source topic, so per-partition ordering is loosely preserved
  and the failure can be traced back to its origin.
- Every DLT publish increments `ledger_kafka_dlt_published_total{topic, dltTopic, exceptionClass}`,
  with the exception class unwrapped to its root cause for a low-cardinality, meaningful tag (e.g.
  `IllegalArgumentException` rather than always `ListenerExecutionFailedException`).
- See [`kafka-topics.md` § Consumer Contract](../eventing/kafka-topics.md#consumer-contract) for
  the full topic-level contract.

## Compensation Strategy

The platform's approach to async/downstream failure is **preventive validation before mutation**
rather than a post-hoc saga/compensation rollback:

- The fraud check runs **synchronously, before** any funds are reserved. If `fraud-service` declines
  the request (either a genuine high-risk decision, or a fallback decline because the service was
  unavailable and no fail-open policy applied), the authorisation is persisted as `DECLINED` and
  **no balance mutation ever occurs** — there is nothing to compensate/roll back.
- **Fraud-driven account lock** is the one corrective action the platform does take automatically:
  if `fraud-service` recommends locking the account (a very high risk score, or a repeated-decline
  pattern within a trailing window) and the account is currently `ACTIVE`, `auth-service` flips the
  account to `LOCKED` in the **same transaction** as the decline (`FraudOrchestrator` /
  `AuthorisationTransactionalExecutorImpl`). This is a preventive control against further abuse, not
  a compensation for an already-applied side effect.
    - Once `LOCKED`, subsequent authorise attempts short-circuit with `reasonCode=ACCOUNT_LOCKED`
      **before** any fraud-service call is made.
    - There is no automatic unlock — unlocking a `LOCKED` account is a manual DB operation today.
- **Fail-open policy** (`FraudOrchestrator`, disabled by default via `fraud.fail-open.enabled`):
  when `fraud-service` is unavailable, a narrowly-scoped allow-list can approve small transactions
  for pre-configured trusted account ids (`fraud.fail-open.max-amount`,
  `fraud.fail-open.trusted-account-ids`) instead of declining outright — tagged with reason
  `FRAUD_UNAVAILABLE_TRUSTED_TINY_AMOUNT` and a reduced risk score (`40`) for auditability. This is
  a deliberate business-policy trade-off (availability vs. caution), not a correctness compensation.
- **Outbox publish failures** are the one case that is genuinely "async failure after a committed
  state change" — the domain state change (e.g. `AUTHORISED`) has already committed, and only the
  downstream Kafka publish is pending/retrying. This is handled by the outbox retry mechanism (see
  [Outbox Retry + DLT Flow](#outbox-retry--dlt-flow)) rather than a compensating transaction,
  because the domain state itself is correct and complete — only the notification to
  `ledger-service` is delayed.

## Degradation Modes

| Dependency down | What still works |
|-------------------|----------------------|
| **fraud-service** | `auth-service`'s authorise endpoint still responds — the circuit breaker fails fast after sustained failures, and the fallback path returns a deterministic `unavailable` decision. By default (`fail-open.enabled: false`) this results in a `DECLINED` authorisation (fail-closed); if fail-open is enabled, small transactions from trusted accounts are approved instead. Capture and reverse are unaffected (they don't call fraud-service). |
| **Kafka** | `auth-service`'s authorise/capture/reverse APIs still work synchronously — the domain state change and outbox row commit to Postgres regardless of Kafka's availability. The outbox publisher simply accumulates backlog (`auth_outbox_backlog` rises) and retries per `OutboxBackoffPolicy` once Kafka recovers. `ledger-service` projections stall during the outage but catch up once Kafka is back. |
| **Redis** | `auth-service`'s idempotency response cache falls through to normal DB-level execution and the durable unique-constraint/event-lookup conflict path — described as "best-effort, not a hard failure" (higher latency/load, not an outage) in [ADR 0007](../decisions/0007-idempotency-store-and-key-policy.md). `fraud-service`'s velocity-based rules have a harder dependency on Redis for the sliding-window rate check (see [ADR 0006](../decisions/0006-redis-sliding-window-rate-limit.md)). |
| **Postgres** | Hard dependency for all three services — no degradation path exists; requests to the owning service fail directly. |

## Observability Tie-In

| Pattern | Metric(s) |
|---------|-------------|
| Circuit breaker (fraud) | `resilience4j_circuitbreaker_calls_seconds_count{name="fraudService", kind="not_permitted"\|"failed"}` — circuit state and call outcomes, visualized in the `Payment Platform Overview` dashboard's Fraud Check row. |
| Fraud call latency/outcome | `auth_fraud_check_duration_seconds` (client-side, `auth-service`); `fraud_check_duration_seconds{outcome}` / `fraud_decisions_total{outcome}` (server-side, `fraud-service`). |
| Outbox retry/backlog | `auth_outbox_backlog` (gauge), `auth_outbox_publish_lag_seconds` (histogram), `auth_outbox_publish_attempts_total{result="success"\|"retry"\|"failed"}` (counter). |
| Kafka consumer retry/DLT | `ledger_kafka_dlt_published_total{topic, dltTopic, exceptionClass}`; consumer throughput/lag via `kafka_consumer_fetch_manager_records_lag_max{application, topic}`. |
| Idempotency cache | `auth_idempotency_cache_total{result=hit\|miss\|error}`. |
| Concurrency conflicts | `auth_concurrency_conflict_total{operation=authorise\|capture\|reverse, type=idempotency_race\|optimistic_lock}` — see [`concurrency-consistency.md`](./concurrency-consistency.md#conflict-handling). |

All of these are scraped from each service's `/actuator/prometheus` endpoint and visualized in the
`payment-platform-overview.json` Grafana dashboard (`infra/grafana/dashboards/`). Full metric/query
definitions live in [`docs/observability/telemetry.md`](../observability/telemetry.md#critical-panels-golden-signals--business-critical).

## Operational Playbook Links

- [`docs/observability/runbook.md`](../observability/runbook.md) — top 5 failure scenarios with
  detect/mitigate guidance:
    1. Auth API latency/error spike
    2. Fraud timeout / circuit open
    3. Outbox backlog / publish lag spike
    4. Kafka consumer lag growth (ledger falling behind)
    5. Duplicate/idempotency conflicts
- [`docs/flows/failure-scenarios.md`](../flows/failure-scenarios.md) — detailed scenario catalog
  (duplicate requests, client timeout after commit, outbox publish transient failure, duplicate
  event delivery, invalid state transition, partial downstream failure, fraud decline + account
  auto-lock), plus a quick triage checklist.
- [`outbox-backlog-recovery.md`](../flows/outbox-backlog-recovery.md) — detect → triage → retry →
  manual-replay procedure specifically for a growing outbox backlog or a terminally `FAILED` row.
- [`docs/observability/alerts-slos.md`](../observability/alerts-slos.md) — draft alert thresholds
  tied to these same scenarios, and on-call ownership/escalation policy.

## Roadmap / Production Considerations

Recommended production improvements are tracked in
[`docs/roadmap.md`](../roadmap.md#6-production-considerations) and
[Next Steps](../roadmap.md#5-next-steps-prioritized), including:

- Adding jitter to the outbox and Kafka consumer backoff schedules to avoid synchronized retry
  storms across multiple instances (both are currently deterministic, non-jittered delays).
- Evaluating a `@Retry` decorator for the fraud gateway call, deferred for now to avoid amplifying
  load on an already-degraded downstream (see [ADR 0008](../decisions/0008-resilience4j-circuit-breaker-policy.md)
  Alternatives Considered).
- Generic HTTP-level rate limiting and load shedding at an API gateway layer — currently out of
  scope for the single-host `docker-compose` MVP topology (see
  [Next Steps item 4](../roadmap.md#5-next-steps-prioritized)).
- A fuller operational runbook (detailed commands, trace queries, post-incident template) beyond
  today's lightweight top-5-scenarios runbook.
- Formal SLOs/alerting rules (Prometheus Alertmanager) — dashboards and metrics exist today, but
  paging policy and error budgets are not yet defined.
- Automated outbox dead-letter replay and DLT replay tooling, rather than today's manual SQL/replay
  procedures.

