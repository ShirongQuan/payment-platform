# Telemetry Guidelines

This document defines the baseline telemetry standard for the payment platform.

For planned improvements that are not yet implemented, track them in the roadmap: [`docs/roadmap.md`](../roadmap.md).

## 1) Logging Standard

Use structured JSON logs for all services (`auth-service`, `fraud-service`, `ledger-service`, shared libs).

### Required JSON fields

| Field | Type | Required | Notes |
|---|---|---:|---|
| `timestamp` | string (ISO-8601) | Yes | UTC timestamp |
| `level` | string | Yes | `DEBUG`/`INFO`/`WARN`/`ERROR` |
| `service` | string | Yes | Service name |
| `env` | string | Yes | `local`, `dev`, `staging`, `prod` |
| `traceId` | string | Yes | From OpenTelemetry context |
| `spanId` | string | Yes | From OpenTelemetry context |
| `correlationId` | string | Yes | End-to-end request correlation |
| `idempotencyKey` | string | Conditional | Required for idempotent payment endpoints |
| `accountIdMasked` | string | Conditional | Never log raw account ID |
| `eventType` | string | Optional | Domain event type |
| `errorCode` | string | Optional | Stable machine-readable error code |
| `message` | string | Yes | Human-readable event message |

### Sensitive data and masking

- Never log PAN/CVV, secrets, auth tokens, or raw account identifiers.
- `accountId` must be masked before logging (for example `acct_******1234` or deterministic hash).
- `idempotencyKey` is allowed for diagnostics but should be redacted in user-facing tools when needed.

### Example log event

```json
{
  "timestamp": "2026-09-15T09:11:12.123Z",
  "level": "INFO",
  "service": "auth-service",
  "env": "staging",
  "traceId": "9f4e9a9e2cc4ce6d9e8f6f6d4f1e7d12",
  "spanId": "c6d7b2f1ae1b0938",
  "correlationId": "corr-2f3f4d18-1290-4cb3-9f65-b8d3182f2301",
  "idempotencyKey": "idem-45fef9f8-2fa8-4c7f-8865-7f276f8e8f95",
  "accountIdMasked": "acct_******4567",
  "message": "Authorisation completed",
  "eventType": "AUTHORISED"
}
```

## 2) Metrics List

Track these metrics across service and platform layers.

### API and request-path metrics

- HTTP latency: request duration histogram per endpoint and status class.
- HTTP error rate: 4xx and 5xx error ratios.
- Throughput: request count per endpoint.

Current dashboard metrics:
- `http_server_requests_seconds_bucket`
- `http_server_requests_seconds_count`
- `http_server_requests_seconds_sum`
- `auth_requests_total`
- `auth_authorisations_total{status,reason}`

Note: histogram families are queried via `_bucket` and often accompanied by `_count` / `_sum`.

### Dependency and workflow metrics

- Fraud call latency and timeout rate.
- Outbox backlog and publish lag.
- Kafka publish outcomes/failures.
- Idempotency hit rate.

Current dashboard metrics:
- `auth_fraud_check_duration_seconds_bucket` (auth-service client-side fraud call)
- `fraud_check_duration_seconds_bucket` (fraud-service server-side processing)
- `auth_outbox_backlog`
- `auth_outbox_publish_lag_seconds_bucket`
- `auth_outbox_publish_attempts_total{result}`
- `auth_idempotency_cache_total{result}`

Planned/next-phase metric candidates:
- `auth_kafka_publish_failures_total` (dedicated failure-only counter, not currently in dashboard queries)

### Derived indicators

- Idempotency hit rate = `result="hit"` / (`hit` + `miss`).
- Auth failure ratio = failed authorisations / total authorisations.
- Outbox health = backlog size + max publish lag.

## 3) Trace Model

Model traces around the main authorisation path:

1. `POST /authorisations` (entry span)
2. Validate request + idempotency lookup
3. Fraud check call (`auth -> fraud-service` client/server spans)
4. DB transaction spans (read/write + commit)
5. Outbox write span
6. Kafka publish span
7. Response span completion

### Recommended span attributes

- `correlation.id`
- `idempotency.key` (if policy allows)
- `payment.account_masked`
- `payment.operation` (`AUTHORISE`, `CAPTURE`, `REVERSE`)
- `db.system`, `db.statement` (sanitized)
- `messaging.system`, `messaging.destination`, `messaging.operation`

## 4) OTel Setup

## SDK

- Use OpenTelemetry SDK auto-instrumentation where available.
- Propagate W3C Trace Context headers across service boundaries.
- Configure resource attributes (`service.name`, `service.version`, `deployment.environment`).

## Exporter

- Use OTLP exporter from services to OTel Collector.
- Prefer gRPC OTLP in internal networks.

## Collector Pipeline

- Receiver: OTLP (traces/metrics/logs when enabled)
- Processors: batch, memory limiter, optional attribute filters
- Exporters:
  - traces -> Tempo
  - metrics -> Prometheus-compatible path
  - logs -> log backend (when implemented)

Current infra wiring is tracked in roadmap observability sections and infra compose setup. Any missing production-hardening controls go to next-phase roadmap items.

## 5) Dashboards

Dashboard definitions live in `infra/grafana/dashboards/`. The panels previously listed here (API Overview, Fraud Dependency, Outbox & Publishing, Kafka Health, Idempotency) are **rows/panels inside the single `Payment Platform Overview` dashboard**, not separate dashboards.

### Dashboard inventory

| Dashboard | Purpose | Audience | Link | Owner |
|---|---|---|---|---|
| Payment Platform Overview | End-to-end golden signals: API health, fraud dependency, outbox/Kafka pipeline, idempotency | On-call engineers, service owners | http://localhost:3000/d/payment-platform-overview/payment-platform-overview?orgId=1&from=now-5m&to=now&timezone=browser&refresh=auto | Auth-service owner (primary), Payments platform on-call |
| Kafka Exporter Overview | Broker/topic/partition health, consumer lag | Platform/SRE, on-call | http://localhost:3000/d/kafka-exporter-overview/kafka-exporter-overview | Platform/SRE |
| Spring Boot Dashboard | Generic Spring Boot app health (threads, GC, requests) | Service owners | http://localhost:3000/d/7/spring-boot-dashboard | Service owner per app |
| JVM (Micrometer) | JVM memory/GC/thread diagnostics | Service owners | http://localhost:3000/d/5/jvm-micrometer | Service owner per app |
| redis_exporter for redis | Redis health (used for idempotency/velocity) | Auth-service owner, Platform/SRE | http://localhost:3000/d/6/redis-exporter-for-redis | Auth-service owner |

Screenshots (when captured) go under `docs/observability/dashboard-screenshots/` and are referenced per critical panel below.

### Critical Panels (Golden Signals + Business-Critical)

All panels below live in the **Payment Platform Overview** dashboard (`infra/grafana/dashboards/payment-platform-overview.json`).

#### 1. Auth latency / error rate

| | |
|---|---|
| Panel(s) | `HTTP p95 latency`, `HTTP 5xx error rate`, `Auth request outcomes (last 5m)` |
| Metric/query | `histogram_quantile(0.95, sum by (le, application) (rate(http_server_requests_seconds_bucket{uri!~".*(prometheus\|health).*"}[5m])))` <br> `sum by (application) (rate(http_server_requests_seconds_count{uri!~".*(prometheus\|health).*", status=~"5.."}[5m]))` |
| Why it matters | Direct customer-facing latency and failure signal for the authorisation API; primary golden signal for API health. |
| Normal range | p95 <= 300ms; 5xx rate ~0 req/s under normal load (see candidate SLOs in `alerts-slos.md`). |
| Alert tie-in / runbook | Alert: "API 5xx ratio > 2% for 10m" / "API p99 latency > 1.5s for 10m" (see `alerts-slos.md`). Runbook: [Scenario 1](./runbook.md#top-5-failure-scenarios). |
| Screenshot | ![Auth latency and error rate](./dashboard-screenshots/auth-latency-error-rate.png) |

#### 2. Fraud timeout rate

| | |
|---|---|
| Panel(s) | `Circuit breaker call outcomes (fraudService)`, `Fraud check latency (as observed by auth-service)` |
| Metric/query | `sum(rate(resilience4j_circuitbreaker_calls_seconds_count{name="fraudService", kind="not_permitted"}[1m]))` (circuit open / fast-fail) <br> `sum(rate(resilience4j_circuitbreaker_calls_seconds_count{name="fraudService", kind="failed"}[1m]))` <br> `auth_fraud_check_duration_seconds_bucket` |
| Why it matters | Detects fraud dependency degradation before it causes broad authorisation failures; circuit breaker state indicates automatic protection is engaged. |
| Normal range | `not_permitted` and `failed` rates ~0/min; circuit breaker state = closed; p95 fraud latency <= 200ms (see `alerts-slos.md`). |
| Alert tie-in / runbook | Alert: "Fraud timeout ratio > 5% for 10m" / "Circuit breaker open for fraud dependency for > 5m" (see `alerts-slos.md`). Runbook: [Scenario 2](./runbook.md#top-5-failure-scenarios). |
| Screenshot | ![Fraud timeout rate](./dashboard-screenshots/fraud-timeout-rate.png) |

#### 3. Outbox lag

| | |
|---|---|
| Panel(s) | `Outbox publish lag`, `Outbox backlog` |
| Metric/query | `histogram_quantile(0.95, sum by (le) (rate(auth_outbox_publish_lag_seconds_bucket{application="auth-service"}[5m])))` <br> `auth_outbox_backlog` |
| Why it matters | Growing lag/backlog means downstream ledger events are delayed, risking stale account balances and reconciliation drift. |
| Normal range | Backlog near 0; max publish lag <= 120s (see `alerts-slos.md`). |
| Alert tie-in / runbook | Alert: "Outbox backlog > 5,000 messages for 15m" / "Outbox max lag > 300s for 10m" (see `alerts-slos.md`). Runbook: [Scenario 3](./runbook.md#top-5-failure-scenarios). |
| Screenshot | ![Outbox lag](./dashboard-screenshots/outbox-lag.png) |

#### 4. DLT increase rate

| | |
|---|---|
| Panel(s) | `DLT publishes/min by topic and exception` |
| Metric/query | `sum by (topic, dltTopic, exceptionClass) (rate(ledger_kafka_dlt_published_total{application="ledger-service"}[5m]) * 60)` |
| Why it matters | Rising dead-letter volume indicates poison messages or a systemic consumer bug; unresolved DLT growth means data is not being processed into the ledger. |
| Normal range | ~0 messages/min; any sustained non-zero rate warrants investigation. |
| Alert tie-in / runbook | Alert: "DLT rate > baseline threshold for 30m" (see `alerts-slos.md`). Runbook: [Scenario 4](./runbook.md#top-5-failure-scenarios) (related consumer lag/DLT triage). |
| Screenshot | ![DLT increase rate](./dashboard-screenshots/dlt-increase-rate.png) |

#### 5. Kafka consumer lag (auth.events)

| | |
|---|---|
| Panel(s) | `Consumer throughput per min vs. lag (auth.events)` |
| Metric/query | `sum by (topic) (kafka_consumer_fetch_manager_records_lag_max{application="ledger-service", topic="auth.events"})` <br> `sum by (topic) (rate(ledger_kafka_consumer_messages_received_total{application="ledger-service"}[5m]) * 60)` |
| Why it matters | Growing lag means the ledger consumer is falling behind the auth event stream, delaying ledger updates and risking staleness independent of outbox/publish health. |
| Normal range | Lag near 0 and stable; throughput tracks upstream publish rate. |
| Alert tie-in / runbook | Alert: "Consumer lag growing continuously for 15m" (see `alerts-slos.md`). Runbook: [Scenario 4](./runbook.md#top-5-failure-scenarios). |
| Screenshot | ![Kafka consumer lag](./dashboard-screenshots/kafka-consumer-lag.png) |

#### 6. Idempotency conflict / duplicate rate

| | |
|---|---|
| Panel(s) | `Idempotency cache outcomes/min by result`, `Idempotency cache hit rate (%)` |
| Metric/query | `sum by (result) (rate(auth_idempotency_cache_total{application="auth-service"}[5m]) * 60)` <br> `100 * sum(rate(auth_idempotency_cache_total{application="auth-service", result="hit"}[5m])) / clamp_min(sum(rate(auth_idempotency_cache_total{application="auth-service"}[5m])), 1e-9)` |
| Why it matters | A rising conflict/duplicate share indicates aggressive client retries or a broken idempotency key strategy, risking either duplicate side effects or unnecessary rejections. |
| Normal range | Hit rate stable at baseline; `conflict`/`error` result share ~0% under normal load. |
| Alert tie-in / runbook | Alert: "Idempotency conflict rate increase > 3x baseline for 30m" (see `alerts-slos.md`). Runbook: [Scenario 5](./runbook.md#top-5-failure-scenarios). |
| Screenshot | ![Idempotency conflict rate](./dashboard-screenshots/idempotency-conflict-rate.png) |

#### 7. DB concurrency conflicts

| | |
|---|---|
| Panel(s) | `Concurrency conflicts/min by operation and conflict type` |
| Metric/query | `sum by (operation, type) (rate(auth_concurrency_conflict_total{application="auth-service"}[5m]) * 60)` |
| Why it matters | Spikes indicate optimistic-locking contention or lock waits under load, which is a key signal for the "is it DB?" triage branch and can precede latency degradation. |
| Normal range | Low, steady baseline rate; sharp increases correlate with traffic spikes or schema/index regressions. |
| Alert tie-in / runbook | No dedicated alert defined yet; track as candidate in `alerts-slos.md` next-phase items. Not in the top-5 runbook scenarios; use general triage in [`runbook.md`](./runbook.md). |
| Screenshot | ![DB concurrency conflicts](./dashboard-screenshots/db-concurrency-conflicts.png) |

Add screenshot files to `docs/observability/dashboard-screenshots/` using the file names referenced above.









