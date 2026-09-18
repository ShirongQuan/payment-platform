# Payment Platform Demo Guide

A guided, positive-first walkthrough of `payment-platform` for showing off the authorise → capture
/ reverse flow, event-driven ledger projection, and the observability stack (metrics, traces,
dashboards) — including how to intentionally demonstrate reliability behavior like outbox lag and
dead-letter-topic (DLT) routing.

Before starting, make sure the platform is running — see the
[Getting Started guide](./README.md), any of the three run modes work for this demo.

## Table of Contents

- [Demo Objectives](#demo-objectives)
- [Start the Platform](#start-the-platform)
- [Verify Service Health](#verify-service-health)
- [Authorize a Payment](#authorize-a-payment)
- [Capture a Payment](#capture-a-payment)
- [Reverse or Refund a Payment](#reverse-or-refund-a-payment)
- [Inspect Kafka Events](#inspect-kafka-events)
- [View Metrics and Dashboards](#view-metrics-and-dashboards)
- [Inspect Distributed Traces](#inspect-distributed-traces)
- [Correlate a Log Line to a Trace](#correlate-a-log-line-to-a-trace)
- [Demonstrate a Failure Scenario](#demonstrate-a-failure-scenario)
- [Reset the Demo](#reset-the-demo)

## Demo Objectives

`By the end of this walkthrough you will have shown:
`

- a complete authorise → capture and authorise → reverse payment journey, with real balance
  movement in Postgres
- idempotent request handling (safe retries with the same idempotency key)
- reliable event publishing from `auth-service` to `ledger-service` via the outbox pattern and
  Kafka, including how to *watch* that pipeline in Grafana (backlog, publish lag)
- distributed tracing across `auth-service → fraud-service` and the asynchronous
  outbox-to-Kafka-to-ledger hop, in Tempo
- resilience behavior in action: a fraud-service outage tripping auth-service's circuit breaker,
  and a malformed event being routed to the dead-letter topic

## Prerequisites

- The platform running via any run mode from the [Getting Started guide](./README.md) (pre-published
  images are the fastest for a live demo).
- `curl` and `jq` for readable request/response output.
- Optional: `kcat` (`brew install kcat`) if you want to manually inject a DLT-bound Kafka record
  instead of using the scripted version in [Demonstrate a Failure Scenario](#demonstrate-a-failure-scenario).

> **Tip:** every `curl` request below has a point-and-click equivalent in Swagger UI, bundled with
> auth-service at `http://localhost:9000/swagger-ui.html`. It's a convenient alternative for live
> demos — expand an endpoint, click **Try it out**, fill in the JSON body, and **Execute** — with no
> terminal typing in front of an audience. Ledger-service's read-only endpoints are also documented
> at `http://localhost:9020/swagger-ui.html`.

## Start the Platform

Fastest path — pre-published images plus infra, all in one command:

```bash
cd /Users/shirongquan/Documents/Shirong/Projects/payment-platform
docker compose -f infra/docker/docker-compose.yml -f infra/docker/docker-compose.app.yml up -d
```

Give it a minute for Kafka's 3-broker cluster and the application services' healthchecks to settle,
then continue to [Verify Service Health](#verify-service-health).

## Verify Service Health

```bash
curl -s http://localhost:9000/actuator/health | jq '.status'
curl -s http://localhost:9010/actuator/health | jq '.status'
curl -s http://localhost:9020/actuator/health | jq '.status'
```

Each should print `"UP"`. You can also confirm every container is healthy:

```bash
docker compose -f infra/docker/docker-compose.yml -f infra/docker/docker-compose.app.yml ps
```

## Authorize a Payment

All requests below can also be sent through Swagger UI
(`http://localhost:9000/swagger-ui.html`) instead of `curl` — useful if you'd rather click through
`POST /accounts`, `POST /authorisations`, `.../captures`, and `.../reversals` live in front of an
audience. The `curl` versions are kept here so responses can be piped through `jq` and reused in
later steps.

Create an account and fund it:

```bash
curl -sS -X POST "http://localhost:9000/accounts" \
  -H "Content-Type: application/json" \
  -d '{"currencyCode":"GBP"}' | jq
```

Save the returned `accountId`, then deposit funds:

```bash
ACCOUNT_ID="<paste-accountId-here>"

curl -sS -X POST "http://localhost:9000/accounts/${ACCOUNT_ID}/deposits" \
  -H "Content-Type: application/json" \
  -d '{"amount":100.00,"currencyCode":"GBP"}' | jq
```

Now authorise a payment:

```bash
curl -sS -X POST "http://localhost:9000/authorisations" \
  -H "Content-Type: application/json" \
  -d '{
    "accountId":"'"${ACCOUNT_ID}"'",
    "idempotencyKey":"demo-auth-001",
    "amount":10.00,
    "currencyCode":"GBP",
    "merchantReference":"order-demo-001"
  }' | jq
```

Expected response (`200 OK`):

```json
{
  "id": "uuid",
  "accountId": "uuid",
  "idempotencyKey": "demo-auth-001",
  "amount": 10.00,
  "currencyCode": "GBP",
  "merchantReference": "order-demo-001",
  "status": "AUTHORISED",
  "createdAt": "2026-09-17T10:00:00Z",
  "updatedAt": "2026-09-17T10:00:00Z"
}
```

Save the returned `id` as `AUTH_ID`. Behind the scenes: `auth-service` synchronously calls
`fraud-service`'s `POST /fraud/check`, reserves funds (`availableBalance` decreases,
`reservedBalance` increases), persists an `AUTHORISATION_AUTHORISED` domain event, and writes a
matching `outbox_event` row in the same database transaction — see
[`docs/eventing/outbox-pattern.md`](../eventing/outbox-pattern.md) for the full mechanism.

**Nice thing to point out:** re-sending the exact same request (same `idempotencyKey`, same
payload) is completely safe — it returns the same stored result instead of creating a second
authorisation.

```bash
curl -sS -X POST "http://localhost:9000/authorisations" \
  -H "Content-Type: application/json" \
  -d '{
    "accountId":"'"${ACCOUNT_ID}"'",
    "idempotencyKey":"demo-auth-001",
    "amount":10.00,
    "currencyCode":"GBP",
    "merchantReference":"order-demo-001"
  }' | jq
```

## Capture a Payment

```bash
AUTH_ID="<paste-id-from-authorisation-response>"

curl -sS -X POST "http://localhost:9000/authorisations/${AUTH_ID}/captures" \
  -H "Content-Type: application/json" \
  -d '{"idempotencyKey":"demo-capture-001"}' | jq
```

Expected response:

```json
{
  "authorisationId": "uuid",
  "idempotencyKey": "demo-capture-001",
  "capturedAmount": 10.00,
  "currencyCode": "GBP",
  "status": "CAPTURED",
  "updatedAt": "2026-09-17T10:05:00Z"
}
```

The authorisation transitions `AUTHORISED → CAPTURED`, `reservedBalance` decreases by the captured
amount, and another outbox event (`AUTHORISATION_CAPTURED`) is queued for publishing.

## Reverse or Refund a Payment

The MVP scope supports full **reversal** (releasing a reservation), not a partial refund — see
[README § Domain Scope](../../README.md#domain-scope) for what's in/out of scope today. To
demonstrate it, authorise a *second* payment first (an already-`CAPTURED` authorisation can't also
be reversed):

```bash
curl -sS -X POST "http://localhost:9000/authorisations" \
  -H "Content-Type: application/json" \
  -d '{
    "accountId":"'"${ACCOUNT_ID}"'",
    "idempotencyKey":"demo-auth-002",
    "amount":5.00,
    "currencyCode":"GBP",
    "merchantReference":"order-demo-002"
  }' | jq

AUTH_ID_2="<paste-id-from-that-response>"

curl -sS -X POST "http://localhost:9000/authorisations/${AUTH_ID_2}/reversals" \
  -H "Content-Type: application/json" \
  -d '{"idempotencyKey":"demo-reverse-001","reasonCode":"CUSTOMER_REQUEST"}' | jq
```

Expect `status: REVERSED`, `reservedBalance` decreasing and `availableBalance` increasing back by
the reserved amount — the reservation is released in full.

## Inspect Kafka Events

Open Kafka UI at `http://localhost:9091` and browse the `auth.events` topic — you should see
`AUTHORISATION_AUTHORISED`, `AUTHORISATION_CAPTURED`, and `AUTHORISATION_REVERSED` records keyed by
`aggregateId` (the authorisation id), each carrying `eventId`/`eventType`/`correlationId` headers.

Confirm the ledger service projected them:

```bash
curl -s "http://localhost:9020/authorisations/${AUTH_ID}" | jq
curl -s "http://localhost:9020/accounts/${ACCOUNT_ID}/events" | jq
```

The second call returns the account's full event timeline as projected by `ledger-service` — a
nice moment to highlight that `ledger-service` never talks to `auth-service` directly; it only
consumes Kafka events, per [`docs/eventing/kafka-topics.md`](../eventing/kafka-topics.md).

## View Metrics and Dashboards

Open Grafana at `http://localhost:3000` (login `admin` / `password`, unless overridden) and select
the **Payment Platform Overview** dashboard. Good panels to narrate live:

| Panel                                                  | What to say                                                                |
|--------------------------------------------------------|----------------------------------------------------------------------------|
| `HTTP p95 latency`, `Auth request outcomes`            | End-to-end request health for the API you just called                      |
| `Fraud check latency`, `Circuit breaker call outcomes` | The synchronous dependency your authorise call just exercised              |
| `Outbox publish lag`, `Outbox backlog`                 | The transactional-outbox pipeline delivering your event to Kafka           |
| `Consumer throughput per min vs. lag (auth.events)`    | `ledger-service` catching up on the events you just produced               |
| `Idempotency cache outcomes/min`                       | The duplicate authorise request you sent earlier showing up as a cache hit |

Prometheus (`http://localhost:9090`) is the underlying data source if you want to run ad hoc
queries, for example:

```text
sum(rate(auth_authorisations_total{status="AUTHORISED"}[5m]))
histogram_quantile(0.95, sum by (le) (rate(auth_outbox_publish_lag_seconds_bucket[5m])))
```

See [`docs/observability/telemetry.md`](../observability/telemetry.md) for the full panel/metric
reference.

## Inspect Distributed Traces

Open Tempo through Grafana Explore (`http://localhost:3000` → Explore → Tempo datasource) and run:

```text
{ resource.service.name = "auth-service" && name = "POST /authorisations" }
```

Open a recent trace to show the `auth-service → fraud-service` client/server span pair for the
fraud pre-check, plus the DB transaction span for the authorisation write.

To show the asynchronous outbox-to-Kafka hop (a separate trace, linked back to the original
request via a span **Link**, since it runs on a later `@Scheduled` publisher tick):

```text
{ resource.service.name = "auth-service" && name = "outbox.kafka.publish" }
```

Open one of those spans and look at its **Links** panel to jump back to the original authorise
request's trace — a good example of how tracing bridges a transactional-outbox handoff. See
[`docs/development/runbook.md`](../development/runbook.md) for more TraceQL examples.

## Correlate a Log Line to a Trace

A nice moment in any demo: show that a single log line, its trace, and its metric data point all
describe the exact same request.

1. Send an authorise request (see [Authorize a Payment](#authorize-a-payment)), then read
   auth-service's log output. In container mode:

   ```bash
   docker compose -f infra/docker/docker-compose.yml -f infra/docker/docker-compose.app.yml \
     logs --tail=200 auth-service | grep "Received authorise request"
   ```

   In IDE mode, just look at the console output of the running `auth-service` process.

2. Find the `[traceId,spanId]` and `[correlationId]` brackets in the matching line, for example:

   ```text
   2026-09-17T10:15:32.418+00:00 DEBUG 1 --- [auth-service] [nio-9000-exec-3] [9f4e9a9e2cc4ce6d9e8f6f6d4f1e7d12,c6d7b2f1ae1b0938] [2f3f4d18-1290-4cb3-9f65-b8d3182f2301] org.example.auth.authorisation.api.AuthorisationController : Received authorise request, accountId=11111111-1111-1111-1111-111111111111, idempotencyKey=demo-auth-001, clientIpAddress=172.19.0.1
   ```

3. Paste the `traceId` into Tempo via Grafana Explore:

   ```text
   { trace:id = "9f4e9a9e2cc4ce6d9e8f6f6d4f1e7d12" }
   ```

   The trace that comes back is the exact request that produced the log line — same
   `auth-service → fraud-service` fraud-check span, same DB transaction span.

4. Switch to the **Payment Platform Overview** dashboard, narrow the time range to that trace's
   timestamp, and point out the corresponding sample in `HTTP p95 latency` /
   `Auth request outcomes (last 5m)` — one log, one trace, one metric point, all describing the same
   authorise call.

A captured example of this three-way view lives at
`docs/observability/dashboard-screenshots/log-trace-metric-correlation.png` (see
[
`docs/observability/telemetry.md` § 6) Correlating Logs, Metrics, and Traces](../observability/telemetry.md#6-correlating-logs-metrics-and-traces)
for the full write-up and the note on today's plain-text log format).

## Demonstrate a Failure Scenario

Two reliability behaviors are easy to demonstrate on demand:

### Circuit breaker opening on a fraud-service outage

```bash
curl -sS -X POST "http://localhost:9010/internal/test/failure-mode" \
  -H "Content-Type: application/json" \
  -d '{"mode":"ALWAYS_503"}'
```

Send a handful of authorise requests — they'll be declined (`FRAUD_SERVICE_UNAVAILABLE`) and, after
enough failures, auth-service's `fraudService` circuit breaker trips `OPEN` (visible immediately in
the `Circuit breaker call outcomes (fraudService)` Grafana panel). Reset it:

```bash
curl -sS -X POST "http://localhost:9010/internal/test/failure-mode" \
  -H "Content-Type: application/json" \
  -d '{"mode":"OFF"}'
```

Send a few more authorise requests to show the breaker recovering `HALF_OPEN → CLOSED`.

### Outbox lag and dead-letter-topic (DLT) routing

To *see* outbox lag build and drain: stop `auth-service`'s outbox scheduler from doing its job by
pausing the service briefly, fire a few authorisations while it's down, then start it back up and
watch `auth_outbox_backlog` and `auth_outbox_publish_lag_seconds` spike then recover in Grafana —
or simply narrate the metric while running the scripted load below.

To trigger a real DLT record (a non-retryable, malformed event that `ledger-service` cannot
process), publish directly onto `auth.events` with `kcat` (per
[
`docs/development/runbook.md`](../development/runbook.md#generating-mock-data-for-ledger-service-kafka-consumer--dlt-metrics)):

```bash
EVENT_ID=$(uuidgen); AGG_ID=$(uuidgen); CORR_ID=$(uuidgen); KEY=$(uuidgen)
echo '{"test":"payload"}' | kcat -P -b localhost:9092 -t auth.events \
  -k "$KEY" \
  -H "eventId=$EVENT_ID" \
  -H "aggregateType=AUTHORISATION" \
  -H "aggregateId=$AGG_ID" \
  -H "eventType=UNSUPPORTED_EVENT_TYPE" \
  -H "occurredAt=$(date -u +%Y-%m-%dT%H:%M:%S.000Z)" \
  -H "correlationId=$CORR_ID"
```

Then confirm it landed on the dead-letter topic and show the `DLT publishes/min by topic and
exception` Grafana panel light up:

```bash
curl -s http://localhost:9020/actuator/prometheus | grep ledger_kafka_dlt_published_total
```

Prefer a fully scripted version of this entire demo (bulk traffic, circuit breaker, DLT, dedup, and
concurrency-conflict phases in one run)? Use:

```bash
cd load-tests
./generate-traffic.sh
```

See [`docs/testing/load-testing.md`](../testing/load-testing.md) for what each phase of that
script produces.

## Reset the Demo

Reset fraud-service's failure mode (also happens automatically if you used
`generate-traffic.sh`, which resets it on exit):

```bash
curl -sS -X POST "http://localhost:9010/internal/test/failure-mode" \
  -H "Content-Type: application/json" \
  -d '{"mode":"OFF"}'
```

For a completely clean slate before the next run (drops all data — Postgres, Kafka, Redis, Grafana
state):

```bash
docker compose -f infra/docker/docker-compose.yml -f infra/docker/docker-compose.app.yml down -v
docker compose -f infra/docker/docker-compose.yml -f infra/docker/docker-compose.app.yml up -d
```

To keep infrastructure state but just stop the containers between demo sessions:

```bash
docker compose -f infra/docker/docker-compose.yml -f infra/docker/docker-compose.app.yml down
```
