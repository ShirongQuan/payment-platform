# Load Testing Report

> Last updated: 2026-09-15
> Scope: current MVP load/traffic evidence from `load-tests/` scripts

## 1) Objective

This report answers three practical questions for the MVP:

- can the platform sustain mixed payment traffic while preserving correctness (idempotency, locking, eventual consistency)?
- do observability signals surface expected reliability patterns (circuit breaker, DLT, duplicate-event dedup, concurrency conflicts)?
- can another engineer reproduce the same traffic profile and verification steps locally?

## 2) Workload model

### Endpoints exercised

- `POST /authorisations`
- `POST /authorisations/{id}/captures`
- `POST /authorisations/{id}/reversals`
- `POST /fraud/check` (indirectly through `auth-service`)
- ledger read checks for verification (`GET /authorisations/{id}`, `GET /accounts/{id}/events`)

### Traffic profile (current scripted baseline)

- source script: `load-tests/generate-traffic.sh`
- phase 1 bulk auth flow: 80 authorisations by default
  - 50 requests at 1 request per 2 seconds
  - 30 requests at 1 request per 1 second
- phase 2 resilience flow: circuit-breaker open/hold/recovery with fraud-service forced `ALWAYS_503`
- phase 3 DLT flow: malformed/unsupported event injection to verify dead-letter routing
- phase 4 dedup flow: repeated same `eventId` publications (`generate-dedup-events.sh`)
- phase 5 concurrency flow: parallel authorise/capture/reverse races (`generate-concurrency-conflicts.sh`)
- default runtime: about 5-6 minutes per full run

### Traffic mix

- mixed by design, not fixed-rate synthetic load
- phase 1 approximates high functional mix (`authorise` dominant, then capture/reversal split by odd/even sequence)
- intended demo ratio for phase 1 is close to `80% authorise / 20% capture+reverse` at operation-entry level

## 3) Environment setup (current evidence)

- runtime topology: local docker-compose stack (`infra/docker/docker-compose.yml`)
- services: auth (`:9000`), fraud (`:9010`), ledger (`:9020`)
- dependencies: Postgres, Kafka, Redis, Prometheus, Grafana, Tempo
- dataset prerequisites: 3 funded `ACTIVE` accounts for the traffic script (or per-script auto-provisioning where supported)

> Note: CPU/memory/disk specs, Postgres tuning values, and exact dataset cardinality were not captured as a fixed benchmark profile in the current MVP artifacts.

## 4) Metrics captured

### Currently captured and verifiable

- reliability/business counters from `/actuator/prometheus`:
  - `auth_concurrency_conflict_total{operation,type}`
  - `auth_idempotency_cache_total{result}`
  - `auth_outbox_publish_attempts_total{result}`
  - `auth_outbox_publish_lag_seconds`
  - `ledger_event_duplicate_skipped_total{eventType}`
  - `ledger_kafka_dlt_published_total`
  - fraud/auth request outcome counters and fraud latency histograms

### Target performance metrics (planned to formalize)

- latency: p50/p95/p99 per endpoint
- throughput: requests/sec by endpoint and end-to-end journey
- error rate: total and by error class
- resource usage: CPU, memory, GC, DB connections, Kafka lag

## 5) Baseline results

### 5.1 Current MVP evidence (observed signal-level)

| Checkpoint | Current status | Evidence source |
|---|---|---|
| Mixed traffic generation completes | Yes | `load-tests/generate-traffic.sh` scripted phases |
| Idempotent replay path exercised | Yes | duplicate request behavior + `auth_idempotency_cache_total` |
| Circuit breaker transitions visible | Yes | auth actuator + fraud forced `503` mode |
| DLT publish path visible | Yes | `ledger_kafka_dlt_published_total` |
| Ledger duplicate-event skip visible | Yes | `ledger_event_duplicate_skipped_total` |
| Concurrency conflict path visible | Yes | `auth_concurrency_conflict_total{type=...}` |

### 5.2 Quantitative baseline table

The repository currently does not include a committed numeric benchmark snapshot for p50/p95/p99 latency, throughput, or resource utilization under a fixed hardware profile.

This is an explicit next step tracked in `docs/roadmap.md`.

## 6) Trade-offs observed and next optimizations

### Observed from existing scripts/docs

- shared-account parallel writes present a concurrency trade-off: optimistic locking preserves correctness, but hot-key contention increases retry churn and tail latency
- fraud failure/latency directly impacts authorization path (by design, protected with timeout + circuit breaker)
- outbox publish lag and consumer retry paths delay ledger convergence until event consumption succeeds

### Optimization candidates

- tune outbox scheduler batch size/poll interval for faster publish lag recovery
- evaluate optimistic vs pessimistic locking throughput trade-offs under controlled load
- move from bash traffic scripts to sustained load tooling (k6/Gatling) for stable percentile reporting
- define and enforce endpoint-level SLO thresholds for regression detection

Roadmap linkage: see `docs/roadmap.md` [Next Steps](../roadmap.md#5-next-steps-prioritized), especially item 5 and item 10.

## 7) Reproducibility steps

Run the same baseline traffic locally:

```bash
cd /Users/shirongquan/Documents/Shirong/Projects/payment-platform/load-tests
./generate-traffic.sh
```

Run focused scenarios:

```bash
cd /Users/shirongquan/Documents/Shirong/Projects/payment-platform/load-tests
./generate-dedup-events.sh
./generate-concurrency-conflicts.sh
```

Quick verification commands:

```bash
curl -s http://localhost:9000/actuator/prometheus | grep auth_concurrency_conflict_total
curl -s http://localhost:9000/actuator/prometheus | grep auth_idempotency_cache_total
curl -s http://localhost:9020/actuator/prometheus | grep ledger_event_duplicate_skipped_total
curl -s http://localhost:9020/actuator/prometheus | grep ledger_kafka_dlt_published_total
```







