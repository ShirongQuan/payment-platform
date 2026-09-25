# Getting Started Images

Screenshots referenced from the root [`README.md`](../../../README.md) "See It In Action" section
and [`demo-guide.md`](../demo-guide.md).

Expected files:

- `demo-authorise-flow.png` — a screenshot of either Swagger UI
  (`http://localhost:9000/swagger-ui.html`) mid-`POST /authorisations` call, or a terminal showing
  the `curl` request/response pair from
  [Demo Guide § Authorize a Payment](../demo-guide.md#authorize-a-payment), demonstrating the
  `200 OK` / `status: AUTHORISED` response shape.
- `demo-kafka-events.png` — Kafka UI (`http://localhost:9091`) browsing the `auth.events` topic,
  showing the `AUTHORISATION_AUTHORISED`/`AUTHORISATION_CAPTURED`/`AUTHORISATION_REVERSED` records
  and their `eventId`/`eventType`/`correlationId` headers, from
  [Demo Guide § Inspect Kafka Events](../demo-guide.md#inspect-kafka-events).
- `demo-grafana-overview.png` — the Grafana **Payment Platform Overview** dashboard
  (`http://localhost:3000`), showing the panel grid described in
  [Demo Guide § View Metrics and Dashboards](../demo-guide.md#view-metrics-and-dashboards) (HTTP
  p95 latency, outbox lag/backlog, consumer throughput, idempotency outcomes).
- `demo-tempo-trace.png` — a Tempo trace waterfall (via Grafana Explore) for a
  `POST /authorisations` request, showing the `auth-service → fraud-service` client/server span
  pair and the DB transaction span, from
  [Demo Guide § Inspect Distributed Traces](../demo-guide.md#inspect-distributed-traces).
- `demo-circuit-breaker.png` — the Grafana `Circuit breaker call outcomes (fraudService)` panel
  showing the breaker tripped `OPEN` during a fraud-service outage, from
  [Demo Guide § Demonstrate a Failure Scenario](../demo-guide.md#demonstrate-a-failure-scenario).
- `demo-dlt-panel.png` — the Grafana `DLT publishes/min by topic and exception` panel spiking after
  a malformed event lands on the dead-letter topic, from
  [Demo Guide § Demonstrate a Failure Scenario](../demo-guide.md#demonstrate-a-failure-scenario).

Add more files here as needed and link them from the relevant section of the root README or the
demo guide.
