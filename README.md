# Payment Platform

A demo-grade payment platform showcasing authorize → capture → reverse flows, built with an
event-driven architecture (transactional outbox + Kafka), an event-sourced ledger projection, and
full observability (OpenTelemetry, Prometheus, Grafana, Tempo).

> **Status:** personal MVP / demo project — see [Status: Implemented vs. Planned](#status-implemented-vs-planned).

[![CI](https://github.com/ShirongQuan/payment-platform/actions/workflows/ci.yml/badge.svg)](https://github.com/ShirongQuan/payment-platform/actions/workflows/ci.yml)
![Java](https://img.shields.io/badge/Java-21-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4-brightgreen)

## Table of Contents

- [What This Project Demonstrates](#what-this-project-demonstrates)
- [Architecture at a Glance](#architecture-at-a-glance)
- [Key Design Highlights](#key-design-highlights)
    - [Domain & Ledger](#domain--ledger)
    - [Eventing & Outbox](#eventing--outbox)
    - [Reliability](#reliability)
    - [Observability](#observability)
    - [API](#api)
    - [Security](#security)
    - [Engineering Practices](#engineering-practices)
- [Quick Start](#quick-start)
- [Demo Journey](#demo-journey)
- [Status: Implemented vs. Planned](#status-implemented-vs-planned)
- [Documentation Map](#documentation-map)

---

## What This Project Demonstrates

This project simulates a simplified real-world payment platform composed of three services —
**auth-service**, **fraud-service**, and **ledger-service** — communicating synchronously (REST)
and asynchronously (Kafka via the transactional outbox pattern). It demonstrates:

- Payment lifecycle handling (authorise, full capture, full reverse) with a real, guarded state
  machine — partial capture and refunds are tracked as [next steps](docs/roadmap.md#5-next-steps-prioritized)
- An event-sourced ledger projection with strict invariants (balance conservation, currency
  consistency, idempotent posting)
- Reliable event delivery via the outbox pattern and Kafka, including dead-letter handling
- Idempotency and concurrency control under race conditions (optimistic locking + idempotency-race
  recovery)
- Circuit breakers, timeouts, and fail-open policy for a synchronous downstream dependency
  (fraud-service)
- End-to-end observability with distributed tracing, metrics, dashboards, and correlated logs
- A shared cross-cutting library (`shared-spring-lib`) and an automated test suite backed by CI

## Architecture at a Glance

![Container diagram placeholder](docs/architecture/images/containers.png)
*Figure 1: C4 Container diagram — auth-service, fraud-service, ledger-service, Kafka, Postgres,
Redis, and the observability stack.*

The platform follows a C4 model:

- **[System Context (L1)](docs/architecture/system-context.md)** — external actors and system boundary
- **[Containers (L2)](docs/architecture/containers.md)** — services, data stores, messaging
- **[Components (L3)](docs/architecture/components-auth-service.md)** — internals of `auth-service`
- **[Trust Boundaries](docs/architecture/trust-boundaries.md)** — security zones

## Key Design Highlights

### Domain & Ledger

- Event-sourced, single-entry ledger projection: every state-changing event (authorise, capture,
  reverse) produces exactly one durable ledger row; balance conservation, currency consistency, and
  idempotent posting are enforced invariants — evolving toward full double-entry bookkeeping is
  tracked as a future step, not assumed today
- Explicit authorisation/account state machines with guarded transitions (`AUTHORISED → CAPTURED`,
  `AUTHORISED → REVERSED`, terminal `DECLINED`; account `ACTIVE ⇄ LOCKED`)
- 📄 [accounting-model.md](docs/domain/accounting-model.md) · [state-machine.md](docs/domain/state-machine.md)

![State machine placeholder](docs/domain/images/state-machine.png)
*Figure 2: Payment status transitions.*

### Eventing & Outbox

- Transactional outbox guarantees no lost events on service crash — domain state change and outbox
  row commit atomically in one DB transaction
- Kafka topics keyed by the authorisation id for per-aggregate ordering
- At-least-once delivery; `ledger-service` deduplicates by `eventId` for effectively-once projection
- 📄 [outbox-pattern.md](docs/eventing/outbox-pattern.md) · [kafka-topics.md](docs/eventing/kafka-topics.md)

### Reliability

- Optimistic locking + idempotency-race recovery prevent double reservation/capture/reverse under
  concurrent same-key retries
- Circuit breaker + time limiter around the fraud-service call; retry + dead-letter topic around
  Kafka consumption
-

📄 [concurrency-consistency.md](docs/reliability/concurrency-consistency.md) · [resilience-patterns.md](docs/reliability/resilience-patterns.md)

### Observability

- OpenTelemetry traces across auth → fraud → Kafka → ledger, with trace context propagated over
  both HTTP and Kafka headers
- Prometheus + Grafana dashboards for latency, error rate, outbox lag, DLT rate, and idempotency/
  concurrency-conflict counts; every log line carries `traceId`/`spanId`/`correlationId` for
  cross-referencing logs, traces, and metrics for the same request
- 📄 [telemetry.md](docs/observability/telemetry.md) · [runbook.md](docs/observability/runbook.md)

![Grafana dashboard placeholder](docs/observability/images/grafana-dashboard.png)
*Figure 3: Grafana dashboard — request latency, outbox lag, DLT rate.*

![Tempo trace placeholder](docs/observability/images/tempo-trace.png)
*Figure 4: Distributed trace of an authorize request across services (Tempo).*

### API

- Consistent RFC 7807 error model across services, with a service-specific `errorCode`
- `idempotencyKey` request-body semantics for safe retries on every payment-critical endpoint
- 📄 [overview.md](docs/api/overview.md) · [idempotency.md](docs/api/idempotency.md) · [OpenAPI specs](docs/api/openapi/)

### Security

- Threat model covers the platform's trust boundaries and abuse vectors, clearly separating what's
  implemented today from what's planned next
- Data-protection posture (PII handling, masking) and audit/compliance intent documented up front
-

📄 [threat-model.md](docs/security/threat-model.md) · [data-protection.md](docs/security/data-protection.md) · [audit-compliance.md](docs/security/audit-compliance.md)

### Engineering Practices

- `shared-spring-lib` centralizes cross-cutting concerns (correlation ids, RFC 7807 error model,
  idempotency hashing) so all three services stay consistent without copy-paste
- Unit + integration tests (Testcontainers-backed) run in every module, wired into GitHub Actions CI
- Scripted traffic generation (`load-tests/`) reproduces idempotency replay, circuit-breaker
  transitions, DLT routing, and concurrency conflicts on demand, for demos and manual verification
- Every architecture/consistency decision is recorded as a numbered ADR
-

📄 [development.md](docs/getting-started/development.md) · [testing/strategy.md](docs/testing/strategy.md) · [load-tests/](load-tests/) · [decisions/](docs/decisions/README.md)

## Quick Start

Run the full platform with pre-published images:

```bash
docker compose -f infra/docker/docker-compose.yml -f infra/docker/docker-compose.app.yml up -d
```

Check that all services are healthy:

```bash
docker compose -f infra/docker/docker-compose.yml -f infra/docker/docker-compose.app.yml ps
```

➡️ Full setup options (pre-published images, local builds, IDE run), prerequisites, and
troubleshooting are in the **[Getting Started guide](docs/getting-started/README.md)**.

For Docker Compose services, ports, health checks, logs, and environment reset instructions, see
the **[Infrastructure Guide](infra/README.md)**.

## Demo Journey

Follow a guided walkthrough — authorize a payment, capture it, observe the ledger update, and
trace the request across services:

➡️ **[Demo Guide](docs/getting-started/demo-guide.md)**

Preview of what you'll see:

![Sequence diagram placeholder](docs/flows/images/payment-lifecycle.png)
*Figure 5: Authorize → Capture → Ledger posting → Event publish.*

## Status: Implemented vs. Planned

Legend: ✅ Implemented · 🟡 Partial/Basic · 🔵 Planned

| Area                                                         | Status                 | Details                                                                               |
|--------------------------------------------------------------|------------------------|---------------------------------------------------------------------------------------|
| Core payment flow (authorise/capture/reverse)                | ✅ Implemented          | [domain/state-machine.md](docs/domain/state-machine.md)                               |
| Event-driven ledger (outbox + Kafka + DLT)                   | ✅ Implemented          | [eventing/outbox-pattern.md](docs/eventing/outbox-pattern.md)                         |
| Idempotency & concurrency control                            | ✅ Implemented          | [reliability/concurrency-consistency.md](docs/reliability/concurrency-consistency.md) |
| Resilience (circuit breaker, fail-open, retries)             | ✅ Implemented          | [reliability/resilience-patterns.md](docs/reliability/resilience-patterns.md)         |
| Observability (traces, metrics, dashboards, correlated logs) | ✅ Implemented          | [observability/telemetry.md](docs/observability/telemetry.md)                         |
| Automated tests + CI build                                   | ✅ Implemented          | [testing/strategy.md](docs/testing/strategy.md)                                       |
| Load/traffic-generation scripts                              | 🟡 Demo-scale baseline | [load-tests/](load-tests/)                                                            |
| Alerting / SLO enforcement                                   | 🔵 Planned             | [observability/alerts-slos.md](docs/observability/alerts-slos.md)                     |
| AuthN/AuthZ (Spring Security + JWT)                          | 🔵 Planned             | [roadmap next steps #1](docs/roadmap.md#5-next-steps-prioritized)                     |
| API gateway / infra-level rate limiting                      | 🔵 Planned             | [roadmap next steps #4](docs/roadmap.md#5-next-steps-prioritized)                     |
| Reconciliation service                                       | 🔵 Planned             | [roadmap next steps #2](docs/roadmap.md#5-next-steps-prioritized)                     |
| Customer-facing UI                                           | 🔵 Planned             | [roadmap next steps #3](docs/roadmap.md#5-next-steps-prioritized)                     |
| Partial capture / refunds                                    | 🔵 Planned             | [roadmap production considerations](docs/roadmap.md#6-production-considerations)      |
| Multi-region / DR                                            | 🔵 Planned             | [roadmap production considerations](docs/roadmap.md#6-production-considerations)      |

Full detail, rationale, and prioritization live in [`docs/roadmap.md`](docs/roadmap.md).

## Documentation Map

| Area                                                        | Link                                                             |
|-------------------------------------------------------------|------------------------------------------------------------------|
| Getting started, demo, development                          | [docs/getting-started/](docs/getting-started/README.md)          |
| Infrastructure (Docker Compose, ports, observability stack) | [infra/README.md](infra/README.md)                               |
| Architecture (C4 L1–L3, trust boundaries)                   | [docs/architecture/](docs/architecture/README.md)                |
| Payment flows & failure scenarios                           | [docs/flows/](docs/flows/README.md)                              |
| Domain rules (ledger, state machine)                        | [docs/domain/](docs/domain/accounting-model.md)                  |
| Data model (ERD, schema)                                    | [docs/data/](docs/data/data-model.md)                            |
| Eventing (outbox, Kafka)                                    | [docs/eventing/](docs/eventing/outbox-pattern.md)                |
| Reliability (concurrency, resilience)                       | [docs/reliability/](docs/reliability/concurrency-consistency.md) |
| Observability (telemetry, alerts, runbook)                  | [docs/observability/](docs/observability/README.md)              |
| API contracts                                               | [docs/api/](docs/api/README.md)                                  |
| Security                                                    | [docs/security/](docs/security/threat-model.md)                  |
| Testing strategy                                            | [docs/testing/](docs/testing/strategy.md)                        |
| Load tests (how to run)                                     | [load-tests/](load-tests/README.md)                              |
| Load-testing methodology (why, scenarios, results)          | [docs/testing/load-testing.md](docs/testing/load-testing.md)     |
| Architecture Decision Records                               | [docs/decisions/](docs/decisions/README.md)                      |
| Roadmap (status, next steps, production considerations)     | [docs/roadmap.md](docs/roadmap.md)                                |

---

Personal learning project by Shirong Quan.

