# Payment Platform — Engineering Progress & Roadmap

> Last updated: 2026-10-30  
> Scope: Personal engineering project demonstrating payment workflows, distributed-system design, reliability patterns,
> and observability

Legend: ✅ Implemented · 🟡 In progress · 🔵 Planned

## Table of Contents

- [1) Engineering Goal](#1-engineering-goal)
- [2) Implementation Criteria](#2-implementation-criteria)
- [3) Implemented Capabilities](#3-implemented-capabilities)
- [4) Next Steps (Prioritised)](#4-next-steps-prioritised)
- [5) Future Engineering Directions](#5-future-engineering-directions)
- [6) Demo Script Checklist](#6-demo-script-checklist)
- [7) Engineering Focus](#7-engineering-focus)

## 1) Engineering Goal

Demonstrate an end-to-end payment flow through explicit service boundaries and practical distributed-system patterns:

- authorisation, capture, and reversal with realistic state transitions
- balance reservation with concurrency-safe updates
- ledger update via event projection
- event publishing (outbox + Kafka), with trace propagation across HTTP and Kafka hops
- synchronous service-to-service integration (auth-service → fraud-service) with resilience
  (circuit breaker/time limiter/fail-open), backed by a shared cross-service library
  (`shared-spring-lib`) for correlation ids, error envelopes, and idempotency hashing
- idempotency (Redis-accelerated) to prevent duplicate payment processing on the critical path
- observability: OpenTelemetry tracing, Prometheus metrics, Grafana dashboards, and structured
  logging with correlation IDs

## 2) Implementation Criteria

A capability is considered implemented when its intended behaviour is demonstrated through supported workflows,
validated through relevant tests or repeatable scenarios, and documented with its design and operational
characteristics.

---

## 3) Implemented Capabilities

### Core payment flow

- ✅ authorisation endpoint working — `POST /authorisations` reserves funds against an account after a
  synchronous fraud pre-check, persisting `AUTHORISED` or `DECLINED` (insufficient funds, fraud
  decline, or fraud-service-unavailable-without-fail-open).
- ✅ Balance reservation demonstrated — `Account.reservedBalance` is incremented and
  `availableBalance` decremented atomically inside the authorise transaction; `AccountEntity.version`
  (`@Version`) guards the update.
- ✅ Capture and reverse implemented — `POST /authorisations/{id}/captures` moves
  `AUTHORISED -> CAPTURED` (debits `reservedBalance`, settlement is final);
  `POST /authorisations/{id}/reversals` moves `AUTHORISED -> REVERSED` (releases `reservedBalance`
  back into `availableBalance`, restoring the funds that were set aside at authorisation time).
- ✅ Ledger update flow demonstrated — auth-service emits domain events (via the outbox) that
  ledger-service consumes from Kafka topic `auth.events` and exposes as query projections
  (`GET /authorisations/{id}`, `GET /accounts/{id}/events`).
- ✅ End-to-end happy path demoable: create account → deposit → authorise → capture (or reverse),
  with ledger projections reflecting each step.
- ✅ Realistic state machine implemented, not just a happy path:
    - `AuthorisationStatus`: `AUTHORISED → CAPTURED`, `AUTHORISED → REVERSED`, or terminal `DECLINED`
      at creation time. No transitions are allowed once `CAPTURED`/`REVERSED`/`DECLINED`.
    - `AccountStatus`: `ACTIVE ⇄ LOCKED` — an account is auto-locked by auth-service when
      fraud-service recommends it (high risk score or repeated-decline pattern within a trailing
      window); mutating operations are rejected while `LOCKED`.
    - `AuthorisationEventReason` captures *why* a decline/reverse happened (`INSUFFICIENT_FUNDS`,
      `ACCOUNT_LOCKED`, `ACCOUNT_INACTIVE`, `CURRENCY_MISMATCH`, `INVALID_AMOUNT`, fraud-driven
      decline, `CUSTOMER_REQUEST`/`MERCHANT_REQUEST` for reversals, etc.) instead of a single
      generic failure code.

### Reliability patterns

- ✅ Outbox pattern implemented and explained — `OutboxScheduler` polls (batch size 10, 10s delay)
  and publishes via `OutboxKafkaPublisher`, with a claim-lease (30s) to coordinate publication claims and reclaim, and
  Kafka producer configured for `acks=all` + `enable.idempotence=true`.
- ✅ Kafka event publication demonstrated, with **trace context propagated over Kafka**: the
  producer stores/attaches a `traceparent` header on each record so the ledger-service consumer span
  links back to the originating HTTP request span in Tempo, in addition to `correlationId`,
  `eventId`, `aggregateType`, `aggregateId`, `eventType`, `occurredAt`, and `schemaVersion` headers.
- ✅ Dead-letter handling on the consumer side — ledger-service retries failed records 3× with a 2s
  fixed backoff, then routes them to `auth.events.ledger.dlt` via `DeadLetterPublishingRecoverer`
  (non-retryable errors, e.g. malformed JSON/missing headers, go straight to the DLT).
- ✅ **Ledger-side event deduplication** — since Kafka delivery is at-least-once, the same event can
  be redelivered (e.g. after a consumer restart/rebalance, or a producer retry). ledger-service
  guards against double-processing by recording each consumed event's `eventId` in a
  `processed_event` table via `INSERT ... ON CONFLICT DO NOTHING`: the first delivery processes and
  projects the event, any later redelivery of the same `eventId` is detected and skipped, giving
  effectively-once projection semantics on top of at-least-once Kafka delivery.
- ✅ Circuit breaker shown for the auth-service → fraud-service synchronous call: resilience4j
  `@CircuitBreaker` (count-based 10-call window, opens at ≥50% failure/slow-call rate, slow = >100ms)
    + `@TimeLimiter` (250ms) around a `RestClient` call, executed off a dedicated MDC-preserving
      executor. On timeout/open-circuit/error, auth-service treats the check as `UNAVAILABLE` and either
      declines (default) or applies a narrowly-scoped fail-open allow-list (disabled by default).
      Kafka consumer retries are handled separately by ledger-service, as described in the dead-letter handling workflow
      above. The synchronous fraud check uses the circuit breaker and time limiter policy documented
      in [ADR 0008](./decisions/0008-resilience4j-circuit-breaker-policy.md).
- ✅ **Application-layer anti-abuse signal** (business-aware, not generic HTTP throttling):
  fraud-service implements Redis-backed sliding-window velocity rules — IP velocity (`>3` requests
  in `5s` from the same IP → `+40` risk score, reason `IP_VELOCITY_EXCEEDED`) and account velocity
  (`>3` requests in `5s` for the same account → `+30` risk score, reason
  `ACCOUNT_VELOCITY_EXCEEDED`) — computed atomically via a Lua script
  (`ZADD`/`ZREMRANGEBYSCORE`/`ZCARD`) in `VelocityServiceRedisImpl`. These feed the fraud risk
  score rather than rejecting/throttling HTTP traffic directly. Infrastructure-level request limiting is an
  independent [roadmap direction](#5-future-engineering-directions).

### Consistency and concurrency

- ✅ **Optimistic locking** implemented for the full reserve/capture/reverse path: `AccountEntity`
  carries a Hibernate-managed `@Version` column; a losing concurrent update raises
  `ObjectOptimisticLockingFailureException`, translated to `AccountConcurrencyConflictException`
  (`409`, retry hint) for the caller.
- ✅ **Idempotency-driven race recovery**: independent of optimistic locking, a unique DB constraint
  on `authorisation_event(account_id, event_type, idempotency_key)` is the source of truth for
  concurrent *same-key* retries. When a request loses that race
  (`ConcurrentIdempotencyRaceException`), the service re-reads the committed event/entity instead of
  failing, and either replays the winner's response (matching payload) or returns
  `409 IDEMPOTENCY_CONFLICT` (different payload). Both conflict types (`idempotency_race` and
  `optimistic_lock`) are tracked per-operation via `auth_concurrency_conflict_total{operation,type}`.
- ✅ Idempotency demonstrated end-to-end to **prevent duplicate payment processing**:
    - `POST /authorisations`, `POST /authorisations/{id}/captures`,
      `POST /authorisations/{id}/reversals` (auth-service) and `POST /fraud/check` (fraud-service)
      all require a request-body `idempotencyKey`, scoped to `(accountId or authorisationId,
      idempotencyKey)`, with the semantic payload SHA-256-fingerprinted so a same-key-different-
      payload retry is rejected (`409 IDEMPOTENCY_CONFLICT`) instead of silently replayed.
    - auth-service accelerates replay via a **Redis-backed response cache**
      (`IdempotencyService`, 24h TTL, `idempotency.ttl-hours`), so a safe retry short-circuits with a
      cache hit and never re-enters the DB transaction; cache hit/miss/error outcomes are tracked via
      `auth_idempotency_cache_total{result}`.
    - downstream, ledger-service also deduplicates at-least-once Kafka delivery — see
      "Ledger-side event deduplication" under Reliability patterns above.
      The [next steps](#4-next-steps-prioritised) include extending idempotency to account operations and evaluating
      pessimistic locking alongside the implemented optimistic-locking strategy.

### Observability

- ✅ **Distributed tracing (OpenTelemetry)**: all three services export OTLP traces to an
  otel-collector (`infra/otel/otel-collector-config.yml`, gRPC 4317 / HTTP 4318), which forwards to
  Tempo and derives RED-style metrics via a spanmetrics connector (latency histogram buckets from
  10ms to 5s). Sampling probability is configurable per environment
  (`tracing.sampling.probability`, default `1.0`).
- ✅ **Trace propagation over HTTP**: `CorrelationIdInterceptor` (shared-spring-lib) attaches the
  correlation id to outbound `RestClient` calls (auth-service → fraud-service); W3C trace context is
  propagated automatically by Micrometer Tracing/OTel instrumentation on the same call.
- ✅ **Trace propagation over Kafka**: `OutboxKafkaPublisher` persists the originating request's
  `traceparent` on the outbox row and re-attaches it as a Kafka record header at publish time, so the
  ledger-service consumer span is linked to the original HTTP request span rather than starting a
  disconnected trace.
- ✅ **Prometheus metrics**, scraped from each service's `/actuator/prometheus`, including custom
  business metrics (not just JVM/HTTP defaults):
    - `auth_requests_total{result=success|failure}` — authorisation request outcomes.
    - `auth_authorisations_total{status,reason}` — **decline-reason breakdown** (e.g.
      `status=DECLINED,reason=INSUFFICIENT_FUNDS` vs. a fraud-driven decline), not just a pass/fail
      count.
    - `auth_concurrency_conflict_total{operation=authorise|capture|reverse,
      type=idempotency_race|optimistic_lock}` — **lock/idempotency conflict counts** per operation.
    - `auth_fraud_check_duration_seconds` (client-side, auth-service) and
      `fraud_check_duration_seconds{outcome}` / `fraud_decisions_total{outcome}` (server-side,
      fraud-service, outcomes `approve|decline|duplicate|conflict|in_progress|error`) —
      **fraud call latency and error/outcome breakdown** on both sides of the call.
    - `auth_outbox_publish_lag_seconds` (time from event creation to successful Kafka publish),
      `auth_outbox_backlog` (live gauge of NEW/PUBLISHING rows), and
      `auth_outbox_publish_attempts_total{result=success|retry|failed}` — **outbox lag and
      throughput**.
    - ledger-service's DLT counter (`incrementDltPublished`, tagged by topic/DLT topic/exception
      class) — **DLQ count**.
    - `auth_idempotency_cache_total{result=hit|miss|error}` — Redis idempotency cache effectiveness.
- ✅ **Grafana dashboards** (`infra/grafana/dashboards/`): `payment-platform-overview.json`
  (business/platform health — auth outcomes, fraud decisions, outbox lag/backlog, conflicts),
  `spring-boot-stats.json` and `JVM(Micrometer).json` (Actuator/JVM internals),
  `redis-overview.json` (Redis memory/ops), `kafka-exporter-overview.json` (broker/topic/partition
  health) — all provisioned automatically (`infra/grafana/provisioning`).
- ✅ **Structured logging with correlation IDs**: the shared console pattern includes fields for `traceId`, `spanId`, and
  `correlationId` via a shared console pattern
  (`[%X{traceId:-},%X{spanId:-}] [%X{correlationId:-}]`); `CorrelationIdFilter` (shared-spring-lib)
  resolves/generates the correlation id per inbound request and keeps it in MDC across the
  auth-service → fraud-service call and into the outbox/Kafka headers, so a single request can be
  followed across services and async boundaries in logs, traces, and Kafka headers alike.
- 🟡 Load testing scenarios and baseline tooling included — see `load-tests/` scripts for
  traffic generation, duplicate-event generation, and concurrency-conflict generation.

---

## 4) Next Steps (Prioritised)

1. 🔵 Introduce scoped authentication with Spring Security + JWT, enforcing user/admin role separation so
   customer payment APIs require auth and operational replay/reconciliation endpoints are admin-only
2. 🔵 Demonstrate operational correctness by adding a scheduled reconciliation service that cross-checks authorisations,
   outbox events, and ledger entries, flags inconsistencies, and persists reconciliation results (optionally with
   single-run scheduler coordination via Redis/ShedLock).
3. 🔵 Add a customer-facing UI — a minimal web console (account overview, authorise/capture/reverse actions,
   transaction/event history) so the platform can be demoed and exercised without an API client (Swagger/Postman),
   giving the public API its first real external consumer.
4. 🔵 Add an API gateway in front of `auth-service` and `ledger-service` — a single ingress point for routing,
   centralised authentication/authorisation enforcement (pairs with item 1), and generic HTTP-level rate limiting;
   Related transport and infrastructure security extensions are outlined under
   [Future Engineering Directions](#5-future-engineering-directions). This is also the natural point to introduce a
   versioned `/api/v1` context path (today's endpoints are intentionally un-prefixed — see
   [API docs](./api/README.md#services) — as part of the planned external API integration); the gateway would route
   `/api/v1/auth/*`, `/api/v1/fraud/*`, `/api/v1/ledger/*` to each service without requiring the services
   themselves to change their internal paths.
5. 🔵 Add a pessimistic-locking implementation (`SELECT ... FOR UPDATE` /
   `@Lock(LockModeType.PESSIMISTIC_WRITE)`) for reserve/capture/reverse as a side-by-side comparison
   against the current optimistic-locking + idempotency-race-recovery approach (throughput vs.
   contention trade-offs under the existing `load-tests/generate-concurrency-conflicts.sh` scenario)
6. 🔵 Extend idempotency to `POST /accounts` and `POST /accounts/{id}/deposits`
7. 🔵 Expand failure-path tests (timeouts, duplicate events, DLT replay)
8. 🔵 Enforce CI merge gates in GitHub branch protection/rulesets by requiring the `.github/workflows/ci.yml` status
   check on `main`
9. 🔵 Promote `docs/testing/scenarios.md` into executable E2E coverage by automating the critical scenario matrix
10. 🔵 Publish a quantitative load baseline report (fixed env profile + p50/p95/p99 latency, throughput, error rate,
    resource usage) and track regression thresholds release-to-release

---

## 5) Future Engineering Directions

The implemented payment workflows and distributed-system foundation provide opportunities to explore additional
architectural patterns and operational scenarios. These directions complement the prioritised next steps above.

- **Identity and access management:** Introduce Spring Security, JWT-based authentication and customer/admin role
  separation. Further exploration could include service-to-service authentication, secrets management, and audit
  controls appropriate to the deployment context.
- **Infrastructure and API management:** Extend the architecture with gateway routing, API versioning, generic
  HTTP-level rate limiting, TLS, and access policies for Kafka, Redis, and service-owned databases. These infrastructure
  controls are distinct from the implemented fraud-service velocity signals.
- **Operational monitoring:** Build on existing OpenTelemetry tracing, Prometheus metrics, and Grafana dashboards by
  exploring SLOs, alerting rules, error budgets, and operational response workflows.
- **Extended payment workflows:** Explore partial capture, partial reversal, refunds, and settlement-related flows by
  extending the existing state machine, API contracts, and ledger projections. The current full-capture/full-reversal
  model is described in [ADR 0002](./decisions/0002-full-capture-only.md).
- **Concurrency and idempotency:** Extend idempotency semantics to account creation and deposits, and evaluate
  pessimistic locking (`SELECT ... FOR UPDATE`) against the implemented optimistic-locking and idempotency-race recovery
  strategy.
- **Resilience and failure handling:** Compare bounded retry strategies for the synchronous fraud check with the
  existing circuit-breaker and time-limiter approach, considering the effect of retries on downstream load. The current
  policy is documented in [ADR 0008](./decisions/0008-resilience4j-circuit-breaker-policy.md). Extend automated timeout,
  duplicate-event, and DLT-replay scenarios.

---

## 6) Demo Script Checklist

- ✅ Show architecture diagram (high-level)
- ✅ Run authorisation request
- ✅ Show reservation + ledger impact
- ✅ Show outbox record and published Kafka event (with propagated `traceparent`)
- ✅ Re-send same authorisation idempotency key and show safe replay (Redis cache hit, no duplicate
  reservation)
- ✅ Re-send with the same key but a different payload and show `409 IDEMPOTENCY_CONFLICT`
- ✅ Trigger a fraud decline / high-risk score and show the account auto-lock
- ✅ Run `load-tests/generate-concurrency-conflicts.sh` and show
  `auth_concurrency_conflict_total{type=optimistic_lock}` incrementing in Grafana
- ✅ Show traces (Tempo, linked across the HTTP → fraud call → Kafka → ledger consumer hops),
  Prometheus metrics, and the Grafana overview dashboard
- ✅ Show a forced consumer failure landing on the `auth.events.ledger.dlt` dead-letter topic

---

## 7) Engineering Focus

The Payment Platform demonstrates backend engineering and distributed-system design through explicit service boundaries,
payment lifecycle modelling, reliable asynchronous messaging, concurrency and idempotency controls, resilience patterns,
and end-to-end observability. Its documented implementation and repeatable demonstrations illustrate the engineering
decisions behind those capabilities.
