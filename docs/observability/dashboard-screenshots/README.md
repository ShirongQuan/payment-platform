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

## Log/Trace/Metric Correlation Screenshot

For `../telemetry.md` section `6) Correlating Logs, Metrics, and Traces` and
[`docs/getting-started/demo-guide.md`](../../getting-started/demo-guide.md#correlate-a-log-line-to-a-trace):

- `log-trace-metric-correlation.png` — a single composite screenshot (or a short set) showing, side
  by side:
  1. a terminal/log panel with one `auth-service` log line, with `traceId`/`spanId` (from the
     `[traceId,spanId]` bracket) and `correlationId` highlighted/circled;
  2. the matching trace opened in Grafana Explore → Tempo, found via
     `{ trace:id = "<traceId>" }`, showing the same request's span waterfall;
  3. the Payment Platform Overview dashboard's `HTTP p95 latency` (or another relevant) panel at
     the same timestamp, to show the log/trace pair sits inside a visible metric data point.

  Capture steps are in
  [`docs/getting-started/demo-guide.md` § Correlate a Log Line to a Trace](../../getting-started/demo-guide.md#correlate-a-log-line-to-a-trace).




