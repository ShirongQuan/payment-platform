# Containers (C4 Level 2)

## Table of Contents

- [Diagram](#diagram)
- [Runtime units](#runtime-units)
    - [Application services (internal)](#application-services-internal)
    - [Shared library (not independently deployed)](#shared-library-not-independently-deployed)
    - [Platform infrastructure (internal)](#platform-infrastructure-internal)
    - [Observability Stack (internal, separate sub-boundary)](#observability-stack-internal-separate-sub-boundary)
    - [Admin/dev tooling (internal, ops-facing)](#admindev-tooling-internal-ops-facing)
- [Key interactions](#key-interactions)
    - [Synchronous (REST, request/response)](#synchronous-rest-requestresponse)
    - [Asynchronous (Kafka, at-least-once)](#asynchronous-kafka-at-least-once)
    - [Data access](#data-access)
    - [Telemetry (traces vs. metrics)](#telemetry-traces-vs-metrics)
- [Next Phase (Planned)](#next-phase-planned)

## Diagram

<p>
  <a href="https://raw.githubusercontent.com/ShirongQuan/payment-platform/main/docs/architecture/diagrams/containers.svg">
    <img src="diagrams/containers.svg" alt="Containers (C4 Level 2) diagram" width="100%" />
  </a>
</p>

*Figure 2: Containers — the platform's deployable services and infrastructure.*

This view expands the [System Context](./system-context.md) into the main runtime units of the Payment Platform.
The architecture is built around three application services with explicit responsibility and data-ownership
boundaries, coordinated through synchronous REST and asynchronous Kafka messaging.

PostgreSQL, Redis, and Kafka provide persistence and messaging infrastructure, while the observability stack
forms a separate supporting boundary around the payment-processing path.

## Runtime units

### Application services (internal)

| Container                        | Responsibility                                                                                                                                                                                                                                                                                                 | Tech stack                                                                                           | Owned data                                                                                                                                       |
|----------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------|
| **auth-service** (port `9000`)   | Account management; authorise/capture/reverse lifecycle; idempotency enforcement; optimistic-locking concurrency control; calls fraud-service; writes the transactional outbox.                                                                                                                                | Java 21, Spring Boot 4, Spring Data JPA, Spring Kafka (producer), Resilience4j, Flyway, Redis client | `auth_db` (PostgreSQL): `account`, `authorisation`, `authorisation_event`, `outbox_event`. Also owns keys in Redis (idempotency response cache). |
| **fraud-service** (port `9010`)  | Synchronous risk evaluation for a proposed authorisation (velocity + amount-deviation rules); returns approve/decline (+ risk score, optional account-lock recommendation) to auth-service. Not exposed externally.                                                                                            | Java 21, Spring Boot 4, Spring Data JPA, Flyway, Redis (Lua scripts for sliding-window counters)     | `fraud_db` (PostgreSQL): `fraud_evaluation`. Also owns keys in Redis (`fraud:sw:*` velocity counters, amount-baseline cache).                    |
| **ledger-service** (port `9020`) | Consumes `auth-service`'s domain events from Kafka, deduplicates them, and projects them into raw + normalized ledger storage; exposes read-only query endpoints. Receives payment state changes asynchronously through Kafka, so no synchronous service-to-service dependency is required for ledger updates. | Java 21, Spring Boot 4, Spring Kafka (consumer), Spring Data JPA, Flyway, MapStruct                  | `ledger_db` (PostgreSQL): `ledger_event_log`, `ledger_entry`, `processed_event`.                                                                 |

### Shared cross-cutting library

| Container             | Responsibility                                                                                                                                                                                                                                                                                                                       | Tech stack                                            |
|-----------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------|
| **shared-spring-lib** | Common cross-cutting concerns compiled into all three services: RFC 7807 error model, correlation-id propagation (filter + `RestClient` interceptor), idempotency request hashing, currency-code validation. See [ADR 0009: Shared Spring Lib for Cross-Cutting Concerns](../decisions/0009-shared-spring-lib-for-cross-cutting.md). | Java 21 library (Maven module), no standalone runtime |

### Platform infrastructure (internal)

| Container                                       | Responsibility                                                                                                                                                                                                                                                                                                                                                                                                                                                                    | Tech / image                                 | Used by                                            |
|-------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------|----------------------------------------------------|
| **postgres**                                    | PostgreSQL instance hosting three logically separate service-owned databases (`auth_db`, `fraud_db`, `ledger_db`), created via `infra/postgres/init/01-create-databases.sql`. Each application service accesses only its own database; cross-service coordination occurs through APIs and events rather than database access.                                                                                                                                                     | `postgres:16`, port `5432`                   | auth-service, fraud-service, ledger-service        |
| **redis**                                       | Shared low-latency store for two distinct purposes: auth-service's idempotent-response cache (keyed per operation, TTL-based) and fraud-service's sliding-window velocity/rate-limit counters (Lua-script atomic ops). See [ADR 0006: Use Redis for Sliding-Window Rate Limiting (Fraud Service)](../decisions/0006-use-redis-for-sliding-window-fraud-velocity-checks.md) / [ADR 0007: Idempotency store and key policy](../decisions/0007-idempotency-store-and-key-policy.md). | `redis:8.6-alpine`, port `6379`              | auth-service, fraud-service                        |
| **kafka** (3-node KRaft cluster: `kafka-1/2/3`) | Durable, replicated event log for the `auth.events` topic (replication factor 3, min ISR 2) and its dead-letter topic. Decouples auth-service (producer) from ledger-service (consumer).                                                                                                                                                                                                                                                                                          | `apache/kafka:4.3.0`, ports `9092/9094/9095` | auth-service (producer), ledger-service (consumer) |

### Observability Stack (internal, separate sub-boundary)

| Container          | Responsibility                                                                                                                                                                                                                                                                                                                          | Tech / image                       | Used by                                                        |
|--------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------|----------------------------------------------------------------|
| **OTel Collector** | Receives OTLP **traces** from all three services (gRPC/HTTP push) and forwards them to Tempo; derives RED-style span metrics (rate/error/duration) from those traces via the `spanmetrics` connector, exposed for scraping. Does **not** carry application/business metrics — those bypass the Collector entirely (see Prometheus row). | `otel-collector-contrib` `0.112.0` | auth-service, fraud-service, ledger-service (traces, push)     |
| **Tempo**          | Trace storage/query backend behind Grafana's trace views.                                                                                                                                                                                                                                                                               | `2.6.0`                            | OTel Collector (writes), Grafana (reads)                       |
| **Prometheus**     | Two independent scrape paths: (1) each service's own `/actuator/prometheus` endpoint, for app/JVM/business metrics (e.g. `auth_authorisations_total`), and (2) the OTel Collector's `:8889` span-metrics endpoint, for RED metrics derived from traces. Both are pull-based; neither path depends on the other.                         | `v3.5.4`                           | Grafana (reads); all three services + OTel Collector (scraped) |
| **Grafana**        | Dashboards for business metrics, JVM/HTTP internals, Redis, and Kafka health; also the trace-search UI (via the Tempo datasource).                                                                                                                                                                                                      | `11.6`                             | Admin/Ops                                                      |

The observability stack is deliberately outside the payment-processing request path. Its availability affects
operational visibility rather than payment execution, keeping telemetry concerns isolated from application
behaviour.

### Admin/dev tooling (internal, ops-facing)

| Container        | Responsibility                                                 | Tech / image                        | Used by  |
|------------------|----------------------------------------------------------------|-------------------------------------|----------|
| **pgAdmin**      | Human-facing inspection tool for PostgreSQL.                   | `dpage/pgadmin4:9.15` (`5050`)      | Ops only |
| **RedisInsight** | Human-facing inspection tool for Redis.                        | `redis/redisinsight:3.8.0` (`5540`) | Ops only |
| **Kafka UI**     | Human-facing inspection tool for Kafka topics/consumer groups. | `kafbat/kafka-ui:v1.5.0` (`9091`)   | Ops only |

## Key interactions

Cross-service coordination uses two interaction models based on the consistency and coupling required by each
operation: synchronous REST where an immediate decision is needed, and asynchronous Kafka events where
independent processing and eventual consistency are appropriate.

### Synchronous (REST, request/response)

- `Client -> auth-service`: `POST /accounts`, `POST /authorisations`, `POST /authorisations/{id}/captures`,
  `POST /authorisations/{id}/reversals`, account/authorisation reads.
- `auth-service -> fraud-service`: `POST /fraud/check`, wrapped in a Resilience4j circuit breaker + 250ms time
  limiter (`ResilientFraudGateway`); on timeout/circuit-open, auth-service falls back to an `unavailable`
  decision rather than blocking indefinitely.
- `Client -> ledger-service`: read-only `GET /authorisations/{id}`, `GET /accounts/{id}/events` — no writes.
- `auth-service` is the state-changing API boundary for payment and account operations; downstream processing does not
  require synchronous callbacks into it.

### Asynchronous (Kafka, at-least-once)

- `auth-service -> Kafka (auth.events)`: outbox rows (`AUTHORISED` / `CAPTURED` / `REVERSED`) are polled and
  published by `OutboxScheduler` / `OutboxKafkaPublisher`, with `eventId`, `correlationId`, and `traceparent`
  headers for downstream dedup/tracing.
- `Kafka (auth.events) -> ledger-service`: `LedgerKafkaConsumer` consumes and routes by event type; non-retryable
  failures are redirected to `auth.events.ledger.dlt` (dead-letter topic) after 3 retries with 2s backoff (see
  [ADR 0004: Use Separate Main Event and DLT Topics](../decisions/0004-use-event-and-dlt-topics.md)).

### Data access

- Each service connects **only** to its own database. There is no cross-service schema access: services maintain
  strong transactional consistency within their own data boundary and coordinate across service boundaries
  through explicit APIs and events. Ledger state is propagated through the Kafka event stream (see
  [ADR 0003: Store Both Raw and Normalized Ledger Events](../decisions/0003-ledger-raw-and-normalized-events.md)).
- Redis is the only store touched by two different services (auth-service and fraud-service), but each uses a
  disjoint key namespace (`{operation}:idempotency:*` vs `fraud:sw:*` / amount-baseline keys) — there is no
  shared key space or read/write coupling between them.

### Telemetry (traces vs. metrics)

Traces and application metrics follow two independent telemetry paths:

- **Traces (push):** each service's Spring Boot auto-instrumentation exports spans via **OTLP** to the OTel
  Collector, which forwards them to Tempo. The Collector's `spanmetrics` connector also derives RED-style
  (rate/error/duration) metrics *from those spans* and exposes them on its own `:8889` endpoint.
- **Application/business metrics (pull):** each service separately exposes its own Micrometer registry (JVM,
  HTTP server timers, and hand-written business counters/timers such as `auth_authorisations_total`) at
  `/actuator/prometheus`. Prometheus scrapes this endpoint **directly on each service** — this path never goes
  through the OTel Collector.
- Prometheus therefore has two scrape targets per environment: every service's `/actuator/prometheus`, and the
  OTel Collector's `:8889` span-metrics endpoint. Both are pull-based and independent of each other; losing the
  Collector does not stop app-metric scraping, and vice versa.

## Next Phase (Planned)

<p>
  <a href="https://raw.githubusercontent.com/ShirongQuan/payment-platform/main/docs/architecture/diagrams/containers-next-phase.svg">
    <img src="diagrams/containers-next-phase.svg" alt="Next-phase containers diagram" width="100%" />
  </a>
</p>

*Figure 3: Planned next-phase containers — dashed elements are not yet implemented.*

The next phase extends the existing distributed-system foundation with the following capabilities:

- **API Gateway** — single ingress for routing, scope enforcement, and infra-level rate limiting in front of
  `auth-service`/`ledger-service` ([roadmap item 4](../roadmap.md#5-next-steps-prioritized))
- **Identity Provider (OIDC)** — centralized authn/authz (customer vs. admin scopes), validated by the gateway
  and `auth-service` ([roadmap item 1](../roadmap.md#5-next-steps-prioritized))
- **Customer Web App / Admin Operations App** — user-facing applications authenticating through the Identity
  Provider and accessing platform APIs through the API Gateway
  ([roadmap item 3](../roadmap.md#5-next-steps-prioritized))
- **reconciliation-service** — scheduled cross-check of ledger projections against account state (and
  eventually an external processor) to detect and flag drift ([roadmap item 2](../roadmap.md#5-next-steps-prioritized))
- **notification-service** — consumes `auth.events` to deliver email/SMS/webhook notifications on payment
  lifecycle changes
- **External Payment Processor / Card Network (simulated)** — settlement target for authorise/capture, and
  reconciliation's source of truth
- **Config/Secrets Store** and **Object Storage** — supporting infra for secrets management and
  receipt/statement/document storage

This diagram presents the planned architectural direction. Implementation sequencing and priorities are
maintained in the [Roadmap § Next Steps](../roadmap.md#5-next-steps-prioritized).