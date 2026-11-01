# Operational Runbook

## Table of Contents

- [Quick diagnostics](#quick-diagnostics)
- [Database access](#database-access)
- [Redis access](#redis-access)
- [Auth-service networking note](#auth-service-networking-note)
- [Tempo trace queries (TraceQL)](#tempo-trace-queries-traceql)
- [Outbox -> Kafka -> ledger-service span link](#outbox---kafka---ledger-service-span-link)
- [Prometheus queries](#prometheus-queries)
- [Generating mock data for ledger-service Kafka consumer / DLT metrics](#generating-mock-data-for-ledger-service-kafka-consumer--dlt-metrics)
    - [0. Prerequisites](#0-prerequisites)
    - [1. Trigger an immediate DLT publish (non-retryable path)](#1-trigger-an-immediate-dlt-publish-non-retryable-path)
    - [2. Verify the metrics incremented](#2-verify-the-metrics-incremented)
    - [3. Verify the record actually landed in the DLT topic](#3-verify-the-record-actually-landed-in-the-dlt-topic)
    - [4. Generate throughput / consumer-lag data](#4-generate-throughput--consumer-lag-data)
    - [Notes / gotchas](#notes--gotchas)

## Quick diagnostics

Start here during an incident, before jumping to a specialist section below.

```bash
# Container status
docker compose -f infra/docker/docker-compose.yml ps

# Service health
curl -s http://localhost:9000/actuator/health   # auth-service
curl -s http://localhost:9010/actuator/health   # fraud-service
curl -s http://localhost:9020/actuator/health   # ledger-service

# Recent logs (last 100 lines, follow)
docker compose -f infra/docker/docker-compose.yml logs --tail=100 -f
```

Docker Compose logs cover the containerised infrastructure. When application services are started
with `mvn spring-boot:run`, inspect the corresponding service terminal for application logs.

## Database access

```bash
docker exec -it payment-platform-postgres bash
psql -U postgres -d postgres
```

```sql
\l -- list databases
\c auth_db -- switch database
\dt -- list tables in current DB
SELECT now();
\q
```

## Redis access

```bash
docker exec -it redis redis-cli
```

Then, in the Redis CLI, scan for fraud velocity sliding-window keys (pattern documented in
[ADR 0006](../decisions/0006-use-redis-for-sliding-window-fraud-velocity-checks.md)):
`fraud:sw:{dimension}:{name}`, e.g. `fraud:sw:account:{accountId}`):

```text
SCAN 0 MATCH fraud:sw:* COUNT 100
```

`SCAN` is preferable to `KEYS` for operational inspection because it iterates incrementally
instead of running a potentially expensive blocking keyspace scan.

Note: fraud-service connects to Redis database `0` by default
(`SPRING_DATA_REDIS_DATABASE`, see `fraud-service/src/main/resources/application.yml`). ADR 0006
does not itself mandate a specific database index, so confirm the configured value in your
environment before assuming database `1` (or any other non-default index) is in use.

## Auth-service networking note

- `server.forward-headers-strategy=framework` is enabled.
- Deploy behind a trusted proxy/load balancer that strips inbound `X-Forwarded-*` headers from clients and sets
  sanitized values.

## Tempo trace queries (TraceQL)

Useful queries for your project.

**All auth-service traces:**

```traceql
{ resource.service.name = "auth-service" }
```

**Only error traces from auth-service:**

```traceql
{ resource.service.name = "auth-service" && status = error }
```

TraceQL supports filtering by both service name and span status.

**Auth endpoint traces:** to find authorisation request spans, filter by `auth-service` and the
HTTP server span name recorded by the current instrumentation (the auth-service endpoint is
`POST /authorisations` - see `docs/development/local-setup.md`). Confirm the exact span name in
Grafana before using an exact-name filter, since it depends on how the instrumentation names HTTP
server spans:

```traceql
{ resource.service.name = "auth-service" && name = "POST /authorisations" }
```

Filtering by both service and span name is the standard way to find traces for a specific
operation.

**Slow auth requests:**

```traceql
{ resource.service.name = "auth-service" && duration > 200ms }
```

TraceQL supports duration filters on spans.

**Root traces started by auth-service:**

```traceql
{ trace:rootService = "auth-service" }
```

`trace:rootService` is a trace-level intrinsic and is usually more efficient when you want traces whose root service is
auth-service.

**Other useful filters:**

```traceql
{ resource.service.name = "auth-service" && name != "http get /actuator/prometheus" }

{ resource.service.name = "auth-service" && name != "http get /actuator/prometheus" && name != "task outboxScheduler.publishOutboxEvents" }

{ resource.service.name = "fraud-service" && name != "http get /actuator/prometheus" }

{ resource.service.name = "ledger-service" && name != "http get /actuator/prometheus" }
```

**Query by trace ID.** Use either of these:

```traceql
{ trace:id = "41928b92edf1cdbe0ba6594baee5ae9" }
```

or in Grafana Explore, just paste the raw trace ID:

```text
41928b92edf1cdbe0ba6594baee5ae9
```

**Query by span ID.** Use the span intrinsic:

```traceql
{ span:id = "e9dba1c9a0273305" }
```

TraceQL supports scoped intrinsics such as `trace:id`, `span:name`, and link intrinsics like `link:spanID` and
`link:traceID`; span-level filtering is done inside `{ ... }` expressions.

## Outbox -> Kafka -> ledger-service span link

The outbox publisher (`OutboxKafkaPublisher`) can't make the Kafka producer span a direct child of the
original auth request span, because publishing happens later on an unrelated `@Scheduled` thread (no
in-memory trace context survives the outbox table round-trip). Instead it captures the original
request's `traceparent` when the outbox row is created, and attaches it as a span Link on the
`outbox.kafka.publish` producer span when it actually publishes.

**IMPORTANT** — the Link lives on the PUBLISH side, not the original request side:

- `outbox.kafka.publish` is a span in its OWN separate trace (started by the `@Scheduled` publisher
  tick), NOT part of the original `POST /authorisations` trace, even though both are emitted by
  auth-service.
- The Link on `outbox.kafka.publish` points BACK to the original request trace that created the
  outbox row.
- The scheduled outbox publisher starts a separate trace and adds a span link referencing the
  originating request's trace context. The original request trace does not automatically gain a
  child span for publication performed later - so you start from the outbox publish trace and
  follow the link backward to the request trace, rather than the other way around.

To find the `outbox.kafka.publish` spans (these are their own traces):

```traceql
{ resource.service.name = "auth-service" && name = "outbox.kafka.publish" }
```

To view the link in Grafana:

1. Explore -> Tempo datasource -> run the query above to list `outbox.kafka.publish` spans/traces.
2. Open one of those traces and click the `outbox.kafka.publish` span in the waterfall.
3. In the span detail panel, look for the "Links" section - it shows the linked trace/span ID
   (the ORIGINAL request trace that created this outbox event).
4. Click the linked trace ID to jump backward into that original request trace (e.g. to see the
   auth-service -> fraud-service call that happened earlier).

To query in the other direction - given an original request's trace ID, find the outbox publish
span(s) that link back to it:

```traceql
{ link:traceID = "<original-request-trace-id>" }
```

## Prometheus queries

```promql
sum by (result) (rate(auth_requests_total[5m]))

# Approval rate
sum(rate(auth_authorisations_total{status="AUTHORISED"}[5m])) / sum(rate(auth_authorisations_total[5m]))

# Decline breakdown by reason
sum by (reason) (rate(auth_authorisations_total{status="DECLINED"}[5m]))
```

## Generating mock data for ledger-service Kafka consumer / DLT metrics

> **Warning:** Run these commands only against a disposable local or dedicated test Kafka
> environment. They intentionally generate invalid records, DLT traffic, and consumer metrics -
> do not run them against any shared or production-like environment.

Background: ledger-service's `LedgerKafkaConsumer` requires several Kafka **headers** (eventId,
aggregateType, aggregateId, eventType, occurredAt, correlationId) on every `auth.events` record
(see `KafkaHeaderReader`). `kafka-console-producer.sh` doesn't make setting custom headers easy, so
use `kcat` (formerly kafkacat) instead - it supports arbitrary headers via `-H`.

Metrics exercised by this workflow (see `LedgerMetrics`):

- `ledger_kafka_consumer_messages_received_total{topic}` - incremented for every record received,
  valid or not.
- `ledger_kafka_dlt_published_total{topic,dltTopic,exceptionClass}` - incremented when a record is
  routed to `auth.events.ledger.dlt` (see `KafkaConsumerConfig`).

### 0. Prerequisites

```bash
brew install kcat
```

Kafka's external listener is exposed on `localhost:9092` (see `infra/docker/docker-compose.yml`).
ledger-service must be running locally and reachable at `http://localhost:9020`.

### 1. Trigger an immediate DLT publish (non-retryable path)

`LedgerEventProcessor.process(...)` throws `IllegalArgumentException` for any `eventType` that
isn't a known `EventType` enum constant, and `KafkaConsumerConfig` treats
`IllegalArgumentException` as non-retryable - so it skips the 3x/2s retry loop and goes straight to
the DLT. This is the fastest, most deterministic way to produce a DLT record on demand:

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
echo "Sent eventId=$EVENT_ID"
```

Other ways to hit the same non-retryable path, if you want variety in the `exceptionClass` tag or
want to test a specific failure mode:

- Omit a required header entirely (e.g. drop `-H "correlationId=..."`) -> `KafkaHeaderReader`
  throws `IllegalArgumentException: Missing required header correlationId`.
- Use a real `eventType` (e.g. `AUTHORISATION_AUTHORISED`) with an invalid JSON `Value` (e.g.
  `not-json` instead of `{"test":"payload"}`) -> the handler's `objectMapper.readValue(...)` fails
  and it re-throws as `IllegalArgumentException` (see `AuthorisationAuthorisedHandler`).

To generate a *rate* rather than a single point (useful for exercising the "DLT publish rate by
exception type" Grafana panel), loop it:

```bash
for i in {1..10}; do
  EVENT_ID=$(uuidgen); AGG_ID=$(uuidgen); CORR_ID=$(uuidgen); KEY=$(uuidgen)
  echo '{"test":"payload"}' | kcat -P -b localhost:9092 -t auth.events -k "$KEY" \
    -H "eventId=$EVENT_ID" -H "aggregateType=AUTHORISATION" -H "aggregateId=$AGG_ID" \
    -H "eventType=UNSUPPORTED_EVENT_TYPE" -H "occurredAt=$(date -u +%Y-%m-%dT%H:%M:%S.000Z)" \
    -H "correlationId=$CORR_ID"
  sleep 1
done
```

### 2. Verify the metrics incremented

```bash
curl -s http://localhost:9020/actuator/prometheus | grep ledger_kafka
```

Expect something like:

```
ledger_kafka_consumer_messages_received_total{topic="auth.events"} 1.0
ledger_kafka_dlt_published_total{dltTopic="auth.events.ledger.dlt",exceptionClass="IllegalArgumentException",topic="auth.events"} 1.0
```

If `exceptionClass` shows `ListenerExecutionFailedException` instead of the real cause (e.g.
`IllegalArgumentException`), the root-cause-unwrapping logic in
`KafkaConsumerConfig#rootCauseSimpleName` isn't deployed/built - rebuild ledger-service.

### 3. Verify the record actually landed in the DLT topic

If a consume against `-b localhost:9092` comes back empty, don't assume you need to connect
directly to the partition leader - a Kafka bootstrap broker does not need to be the partition
leader. The client retrieves cluster metadata from whichever broker it bootstraps against and
connects to the appropriate broker(s) using their advertised listeners. Instead, work through this
checklist:

- **Topic existence and partition metadata** - confirm the topic exists and see how many
  partitions/replicas it has:

  ```bash
  docker exec kafka-1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka-1:9092 \
    --describe --topic auth.events.ledger.dlt
  ```

- **Broker connectivity and advertised listener configuration** - check that the broker(s) you're
  connecting to have correctly configured `advertised.listeners` for the host/port you're using
  (see `infra/docker/docker-compose.yml`). Broker external ports: kafka-1 -> `localhost:9092`,
  kafka-2 -> `localhost:9094`, kafka-3 -> `localhost:9095`.
- **Whether the DLT record was actually published** - check `ledger_kafka_dlt_published_total` (see
  step 2) and ledger-service logs to confirm the publish actually happened before assuming a
  consume-side problem.
- **Consumer offsets and selected partitions** - make sure you're consuming `-o beginning` (or an
  offset before the record) and not restricting to a partition the record wasn't written to.

For a general inspection, prefer consuming the whole topic rather than restricting to a single
partition, since a record published to another partition would not appear if you hardcode `-p 0`:

```bash
kcat -C -b localhost:9092 -t auth.events.ledger.dlt -o beginning -e \
  -f 'Part:%p Offset:%o Key:%k\nHeaders:%h\nValue:%s\n---\n'
```

This assumes the local Kafka listeners are configured correctly; if it still returns nothing,
work back through the checklist above.

You should see the original headers plus Spring's `kafka_dlt-exception-fqcn`,
`kafka_dlt-exception-cause-fqcn`, `kafka_dlt-exception-message`, `kafka_dlt-original-topic`, etc.
headers attached by `DeadLetterPublishingRecoverer`.

### 4. Generate throughput / consumer-lag data

For the "consumer throughput vs. lag" panel, send valid records (no failure needed) - the easiest
no-op valid event is `AUTHORISATION_DECLINED`, which `LedgerEventProcessor` currently ignores
without touching the DB. This relies on that handler's current behaviour - if
`AuthorisationDeclinedHandler` (or equivalent) is changed to perform DB work, revisit this example
and pick another no-op event type:

```bash
EVENT_ID=$(uuidgen); AGG_ID=$(uuidgen); CORR_ID=$(uuidgen); KEY=$(uuidgen)
echo '{"test":"payload"}' | kcat -P -b localhost:9092 -t auth.events -k "$KEY" \
  -H "eventId=$EVENT_ID" -H "aggregateType=AUTHORISATION" -H "aggregateId=$AGG_ID" \
  -H "eventType=AUTHORISATION_DECLINED" -H "occurredAt=$(date -u +%Y-%m-%dT%H:%M:%S.000Z)" \
  -H "correlationId=$CORR_ID"
```

To see lag climb and then drain (rather than staying flat at ~0):

1. Stop ledger-service.
2. Fire a burst of ~50-100 valid records (loop the command above).
3. Start ledger-service again and watch `kafka_consumer_fetch_manager_records_lag_max` spike then
   fall back to 0 as `ledger_kafka_consumer_messages_received_total`'s rate catches up.

### Notes / gotchas

- ledger-service's Kafka **producer** (used only by `DeadLetterPublishingRecoverer` to republish to
  the DLT) must have `key-serializer: UUIDSerializer` / `value-serializer: StringSerializer`
  explicitly configured in `application.yml` under `spring.kafka.producer`, matching the consumer's
  deserializer types. Without it, don't rely on Spring Boot's implicit defaults for the key type.
- Dashboards live in `infra/grafana/dashboards/ledger-dashboard.json` and are auto-provisioned
  (Grafana folder: "Payment Platform" -> "Ledger Dashboard"); no restart needed to pick up JSON
  changes, but the metrics themselves obviously require ledger-service to be running and scraped.
