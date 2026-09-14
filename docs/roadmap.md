# Payment Platform MVP Progress

> Last updated: 2026-09-14  
> Scope: Personal MVP for demonstration and learning

Legend: ✅ Implemented for MVP · 🟡 Partial/Basic · 🔵 Planned if time permits

## 1) MVP Goal

Demonstrate an end-to-end payment flow with realistic architecture patterns:

- authorization, capture, and reversal with realistic state transitions
- balance reservation with concurrency-safe updates
- ledger update via event projection
- event publishing (outbox + Kafka), with trace propagation across HTTP and Kafka hops
- synchronous service-to-service integration (auth-service → fraud-service) with resilience
  (circuit breaker/time limiter/fail-open), backed by a shared cross-service library
  (`shared-spring-lib`) for correlation ids, error envelopes, and idempotency hashing
- idempotency (Redis-accelerated) to prevent duplicate payment processing on the critical path
- observability: OpenTelemetry tracing, Prometheus metrics, Grafana dashboards, and structured
  logging with correlation IDs

## 2) What “Done for MVP” Means

A feature is MVP-done if it:

1. works in demo scenarios,
2. has basic tests or evidence,
3. is documented clearly with known limitations.

---

## 3) Current MVP Progress

### Core payment flow

- ✅ Authorization endpoint working — `POST /authorisations` reserves funds against an account after a
  synchronous fraud pre-check, persisting `AUTHORISED` or `DECLINED` (insufficient funds, fraud
  decline, or fraud-service-unavailable-without-fail-open).
- ✅ Balance reservation demonstrated — `Account.reservedBalance` is incremented and
  `availableBalance` decremented atomically inside the authorise transaction; `AccountEntity.version`
  (`@Version`) guards the update.
- ✅ Capture and reverse implemented — `POST /authorisations/{id}/captures` moves
  `AUTHORISED -> CAPTURED` (debits `reservedBalance`, settlement is final);
  `POST /authorisations/{id}/reversals` moves `AUTHORISED -> REVERSED` (releases `reservedBalance`
  back without ever touching `availableBalance`, since funds were never debited from it).
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

### Reliability patterns (demo level)

- ✅ Outbox pattern implemented and explained — `OutboxScheduler` polls (batch size 10, 10s delay)
  and publishes via `OutboxKafkaPublisher`, with a claim-lease (30s) to prevent double-publish on
  reclaim, and Kafka producer configured for `acks=all` + `enable.idempotence=true`.
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
- ✅ Circuit breaker/retry shown for the auth-service → fraud-service call: resilience4j
  `@CircuitBreaker` (count-based 10-call window, opens at ≥50% failure/slow-call rate, slow = >100ms)
    + `@TimeLimiter` (250ms) around a `RestClient` call, executed off a dedicated MDC-preserving
      executor. On timeout/open-circuit/error, auth-service treats the check as `UNAVAILABLE` and either
      declines (default) or applies a narrowly-scoped fail-open allow-list (disabled by default).
- ✅ **Application-layer anti-abuse signal** (business-aware, not generic HTTP throttling):
  fraud-service implements Redis-backed sliding-window velocity rules — IP velocity (`>3` requests
  in `5s` from the same IP → `+40` risk score, reason `IP_VELOCITY_EXCEEDED`) and account velocity
  (`>3` requests in `5s` for the same account → `+30` risk score, reason
  `ACCOUNT_VELOCITY_EXCEEDED`) — computed atomically via a Lua script
  (`ZADD`/`ZREMRANGEBYSCORE`/`ZCARD`) in `VelocityServiceRedisImpl`. These feed the fraud risk
  score rather than rejecting/throttling HTTP traffic directly; see
  [Production Considerations](#6-production-considerations) for the separate, currently
  unimplemented concern of generic infra-level rate limiting.

### Consistency/concurrency (demo level)

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
- 🔵 Idempotency intentionally **not** implemented for `POST /accounts` and
  `POST /accounts/{id}/deposits` — see section 4 below; this is a deliberate scope cut, not an
  oversight.
- 🔵 No pessimistic locking (`SELECT ... FOR UPDATE`) implementation exists yet as a comparison
  point against the optimistic path — see "If More Time" below.

### Observability (demo level)

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
- ✅ **Structured logging with correlation IDs**: every log line includes `traceId`, `spanId`, and
  `correlationId` via a shared console pattern
  (`[%X{traceId:-},%X{spanId:-}] [%X{correlationId:-}]`); `CorrelationIdFilter` (shared-spring-lib)
  resolves/generates the correlation id per inbound request and keeps it in MDC across the
  auth-service → fraud-service call and into the outbox/Kafka headers, so a single request can be
  followed across services and async boundaries in logs, traces, and Kafka headers alike.
- 🟡 Load testing baseline included (non-production scale) — see `load-tests/` scripts for
  traffic generation, duplicate-event generation, and concurrency-conflict generation.

---

## 4) Explicit MVP Limitations (Intentional)

These are intentionally out-of-scope for MVP:

- Idempotency on `POST /accounts` and `POST /accounts/{id}/deposits` (account creation/deposits are
  test/setup conveniences in this demo, not the payment-authorisation critical path the idempotency
  work targets) — full idempotency coverage across every endpoint is a production concern, see below
- A generic/pluggable pessimistic-locking mode for reserve/capture/reverse (only optimistic locking
    + idempotency-driven race recovery is implemented)
- Advanced reconciliation and incident automation

---

## 5) Next Steps (Prioritized)

1. 🔵 Implement scoped, realistic authentication with Spring Security + JWT, enforcing user/admin role separation so
   customer payment APIs require auth and operational replay/reconciliation endpoints are admin-only
2. 🔵 Demonstrate operational correctness by adding a scheduled reconciliation service that cross-checks authorizations,
   outbox events, and ledger entries, flags inconsistencies, and persists reconciliation results (optionally with
   single-run scheduler coordination via Redis/ShedLock).
3. 🔵 Extend idempotency to `POST /accounts` and `POST /accounts/{id}/deposits`
4. 🔵 Add a pessimistic-locking implementation (`SELECT ... FOR UPDATE` /
   `@Lock(LockModeType.PESSIMISTIC_WRITE)`) for reserve/capture/reverse as a side-by-side comparison
   against the current optimistic-locking + idempotency-race-recovery approach (throughput vs.
   contention trade-offs under the existing `load-tests/generate-concurrency-conflicts.sh` scenario)
5. 🔵 Expand failure-path tests (timeouts, duplicate events, DLT replay)

---

## 6) Production Considerations

These are known, deliberate gaps versus a real production posture — called out explicitly so they
aren't mistaken for oversights:

- **Full idempotency coverage**: only the payment-critical path (authorise/capture/reverse on
  auth-service, check on fraud-service) is idempotent today. `POST /accounts` and
  `POST /accounts/{id}/deposits` are not — a production system would need idempotency (or at least
  strict input validation/authz) on every mutating endpoint, not just the ones demonstrated here.
- **Auth hardening / compliance**: no authentication or authorization exists on any service (no
  JWT, API key, or mTLS, and no gateway) — every endpoint is reachable directly on its configured
  port. Production would require service-to-service auth, request authn/authz, secrets management,
  and a compliance posture (audit logging, PCI-relevant controls, etc.) appropriate to a real
  payments system.
- **SLOs/alerts**: metrics and dashboards exist (see Observability above), but there are no defined
  SLOs or alerting rules (e.g. Prometheus Alertmanager) — thresholds, paging policy, and error
  budgets would need to be defined for production.
- **Multi-region/DR**: the whole stack runs as a single-region `docker-compose` topology (single
  Postgres, single Redis, one Kafka cluster) with no cross-region replication, failover, or backup/
  restore runbook.
- **Generic HTTP-level rate limiting**: in production, generic/infra-level rate limiting (e.g. "max
  N requests/sec per IP or API key across the whole API surface") belongs at the infrastructure
  layer — an API gateway or reverse proxy — not inside each service's application code, so it
  protects every service uniformly, rejects abusive traffic before it reaches business logic/DB/
  Redis, and can be tuned without redeploying any service. This project intentionally has no such
  gateway/reverse proxy layer. (The velocity-based anti-abuse signal that *does* exist is a
  different, application/business-layer concern — see Reliability patterns above.)

---

## 7) Demo Script Checklist

- ✅ Show architecture diagram (high-level)
- ✅ Run authorization request
- ✅ Show reservation + ledger impact
- ✅ Show outbox record and published Kafka event (with propagated `traceparent`)
- ✅ Re-send same authorization idempotency key and show safe replay (Redis cache hit, no duplicate
  reservation)
- ✅ Re-send with the same key but a different payload and show `409 IDEMPOTENCY_CONFLICT`
- ✅ Trigger a fraud decline / high-risk score and show the account auto-lock
- ✅ Run `load-tests/generate-concurrency-conflicts.sh` and show
  `auth_concurrency_conflict_total{type=optimistic_lock}` incrementing in Grafana
- ✅ Show traces (Tempo, linked across the HTTP → fraud call → Kafka → ledger consumer hops),
  Prometheus metrics, and the Grafana overview dashboard
- ✅ Show a forced consumer failure landing on the `auth.events.ledger.dlt` dead-letter topic

---

## 8) Reviewer Guidance

This project should be assessed as:

- a strong architecture and backend-engineering demo,
- with selective implementation depth,
- and clear understanding of what would be required for production.