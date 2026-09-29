# Dashboard Screenshots

Put Grafana screenshots for `../telemetry.md` here (section `5) Dashboards`, Critical Panels).

Expected file names, one per critical panel:

- `auth-latency-and-error-rate.png` — HTTP p95 latency / 5xx error rate (Done)
- `auth-request-outcome.png` — Authorisation request outcomes (last 5m) (Done)
- `fraud-check.png` — Circuit breaker outcomes / circuit state / fraud check latency (Done)
- `outbox-lag.png` — Outbox publish lag (Done)
- `outbox-backlog.png` — Outbox backlog (Done)
- `dlt-increase-rate.png` — DLT publishes/min by topic and exception (Done)
- `kafka-consumer-lag.png` — Consumer throughput per min vs. lag (auth.events) (Done)
- `idempotency-cache-hit-rate.png` — Idempotency cache hit rate (%) (Done)
- `idempotency-cache-outcomes.png` — Idempotency cache outcomes/min by result (Done)
- `db-concurrency-conflicts.png` — Concurrency conflicts/min by operation and conflict type (Done)

Dashboard links (dashboard-level, not per panel) are documented in `../telemetry.md` under `### Dashboard inventory`.

## Log/Trace/Metric Correlation Screenshots

For `../telemetry.md` section `6) Correlating Logs, Metrics, and Traces` and
[Payment Platform Demo Guide](../../getting-started/demo-guide.md#correlate-a-log-line-to-a-trace):

A single request (`correlationId=e567d636-51fe-414a-938e-93d675f23094`) is followed end-to-end as a
series of screenshots under `trace/`, rather than one composite image. Expected file names, in the
order they're referenced:

- `trace/auth-request-<correlationId>.png` — **Auth request**: the authorise request sent to
  auth-service (e.g. via curl/Postman).
- `trace/auth-log-<correlationId>.png` — **Auth log**: auth-service's log line for the request,
  with `traceId`/`spanId` (from the `[traceId,spanId]` bracket) and `correlationId`
  highlighted/circled.
- `trace/fraud-log-<correlationId>.png` — **Fraud log**: fraud-service's log line for the same
  request.
- `trace/leger-log-<correlationId>.png` — **Ledger log**: ledger-service's log line for the same
  request, after the outbox → Kafka hop.
- `trace/auth-trace-<correlationId>.png` — **Auth trace**: the matching trace opened in Grafana
  Explore → Tempo, found via `{ trace:id = "<traceId>" }`, showing the span waterfall
  (auth-service → fraud-service, plus the DB transaction span).
- `trace/node-graph-<correlationId>.png` — **Node graph**: the same trace's Tempo node graph view.
- `trace/outbox-trace-<correlationId>.png` — **Outbox trace**: the linked outbox-to-Kafka publish
  trace, reached via the span **Link** on the original trace.
- `trace/dashboard-<correlationId>.png` — **Dashboard**: the Payment Platform Overview dashboard's
  `HTTP p95 latency` (or another relevant) panel at the same timestamp, to show the log/trace pair
  sits inside a visible metric data point.

  Capture steps are in
  [Payment Platform Demo Guide § Correlate a Log Line to a Trace](../../getting-started/demo-guide.md#correlate-a-log-line-to-a-trace).




