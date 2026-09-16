# Dashboard Screenshots

Put Grafana screenshots for `../telemetry.md` here (section `5) Dashboards`, Critical Panels).

Expected file names, one per critical panel:

- `auth-latency-error-rate.png` — HTTP p95 latency / 5xx error rate
- `fraud-timeout-rate.png` — Circuit breaker outcomes / fraud check latency
- `outbox-lag.png` — Outbox publish lag / backlog
- `dlt-increase-rate.png` — DLT publishes/min by topic and exception
- `kafka-consumer-lag.png` — Consumer throughput vs. lag (auth.events)
- `idempotency-conflict-rate.png` — Idempotency cache outcomes / hit rate
- `db-concurrency-conflicts.png` — Concurrency conflicts/min by operation and type

Dashboard links (dashboard-level, not per panel) are documented in `../telemetry.md` under `### Dashboard inventory`.



