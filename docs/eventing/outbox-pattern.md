# Outbox Pattern

## Table of Contents

- [Why Outbox (Atomic DB State + Event Intent)](#why-outbox-atomic-db-state--event-intent)
- [Transaction Boundary](#transaction-boundary)
- [Outbox Schema](#outbox-schema)
- [Publisher Flow](#publisher-flow)
- [Delivery Semantics](#delivery-semantics)
- [Retry Policy](#retry-policy)
- [Deduplication](#deduplication)
- [Failure Modes](#failure-modes)
- [Operational Metrics](#operational-metrics)
- [Runbook Links](#runbook-links)
- [Roadmap / Production Considerations](#roadmap--production-considerations)

## Why Outbox (Atomic DB State + Event Intent)

`auth-service` needs to atomically (a) change payment/account state (e.g. `AUTHORISED`,
`CAPTURED`, `REVERSED`) and (b) record the *intent* to publish a domain event describing that
change, without a distributed transaction across Postgres and Kafka. Writing directly to Kafka
inside (or right after) the DB transaction risks two failure modes: the DB commits but the Kafka
send fails (lost event), or the Kafka send succeeds but the DB transaction rolls back (phantom
event for a change that never happened).

The transactional outbox pattern avoids both: the domain state change and the outbox row are
written in the **same local DB transaction**, so they are atomic with respect to each other. A
separate, asynchronous publisher then reads outbox rows and delivers them to Kafka, retrying
independently of the original request. See
[ADR 0001: Use Kafka + Transactional Outbox](../decisions/0001-use-kafka-outbox.md) for the full
decision record.

## Transaction Boundary

- The payment state change (e.g. authorisation created, captured, reversed) and the corresponding
  `outbox_event` row insert happen inside the **same database transaction**.
- `OutboxEventServiceImpl.enqueueAuthorisation(...)` is annotated
  `@Transactional(propagation = Propagation.MANDATORY)` — it must run inside an already-open
  transaction (the caller's authorise/capture/reverse transaction). This makes the coupling
  explicit: the method fails fast if someone calls it outside a transaction, rather than silently
  running as its own.
- The W3C `traceparent` for the originating request is also captured and stored on the outbox row
  at write time, so the eventual Kafka publish can re-link its span back to the original HTTP
  request trace.
- Because the insert is transactional, a rollback of the domain change (e.g. a failed capture)
  rolls back the outbox row too — there is no window where an event exists for a state change that
  never committed.

## Outbox Schema

Table: `outbox_event` (see
`auth-service/src/main/resources/db/migration/V1__init.sql`, extended by
`V3__add_outbox_trace_parent.sql`):

| Column             | Type                     | Purpose                                                                 |
|--------------------|--------------------------|--------------------------------------------------------------------------|
| `event_id`         | `uuid` (PK)              | Unique event identifier; also sent as a Kafka header for dedup.          |
| `version`          | `bigint`                 | Optimistic-lock version column (JPA `@Version`).                        |
| `aggregate_type`   | `varchar(20)`            | e.g. `AUTHORISATION`.                                                   |
| `aggregate_id`     | `uuid`                   | The authorisation id — also used as the Kafka **message key**.          |
| `event_type`       | `varchar(30)`            | e.g. `AUTHORISED`, `CAPTURED`, `REVERSED`.                               |
| `payload`          | `jsonb`                  | Event body serialized as JSON.                                          |
| `status`           | `varchar(30)`            | Lifecycle state: `NEW` → `PUBLISHING` → `PUBLISHED` / `FAILED`.          |
| `retry_count`      | `int`                    | Number of failed publish attempts so far.                                |
| `last_error`       | `varchar(100)`           | Truncated error message from the most recent failed attempt.            |
| `created_at`       | `timestamptz`            | Row creation time (start of publish-lag measurement).                   |
| `published_at`     | `timestamptz`            | Set when the row transitions to `PUBLISHED`.                            |
| `next_attempt_at`  | `timestamptz`            | Earliest time the row is eligible to be (re-)claimed for publish.       |
| `idempotency_key`  | `varchar(30)`            | Caller-supplied idempotency key, part of the uniqueness constraint.      |
| `correlation_id`   | `uuid`                   | Correlates this event with the originating request across services.     |
| `claimed_at`       | `timestamptz`            | When a publisher instance claimed this row for in-flight publishing.    |
| `claim_until`      | `timestamptz`            | Claim lease expiry — enables stale-claim recovery.                       |
| `trace_parent`     | `varchar` (added in V3)  | W3C `traceparent` of the originating request, re-attached at publish.   |

Constraints/indexes:

- Unique index `uq_outbox_event_aggregateid_eventtype_idempotency` on
  `(aggregate_id, event_type, idempotency_key)` — prevents duplicate outbox rows for the same
  logical event.
- `idx_outbox_event_claim` on `(status, next_attempt_at, created_at)` — supports efficient polling
  for due, unclaimed rows.
- `idx_outbox_event_claim_until` on `(status, claim_until)` — supports efficient stale-claim
  recovery scans.

## Publisher Flow

`OutboxScheduler` (auth-service) drives the publish loop:

1. **Poll** every `outbox.publisher.delay-ms` (default `10s`).
2. **Reclaim stale claims**: any `PUBLISHING` row whose `claim_until` has passed (e.g. the instance
   that claimed it crashed mid-publish) is reset back to `NEW` automatically — no operator action
   needed.
3. **Batch + claim**: up to `outbox.publisher.batch-size` (default `10`) due `NEW` rows
   (`next_attempt_at <= now`) are selected and atomically claimed (moved to `PUBLISHING`, with
   `claimed_at`/`claim_until` set) via `OutboxEventServiceImpl`, using a claim-lease mechanism
   rather than row-level `SELECT ... FOR UPDATE SKIP LOCKED`.
4. **Publish**: `OutboxKafkaPublisher` sends each claimed row to Kafka topic `auth.events`, using
   `aggregateId` as the message key and attaching headers (`eventId`, `aggregateType`,
   `aggregateId`, `eventType`, `occurredAt`, `correlationId`, `schemaVersion`, `traceparent`).
5. **Mark sent**: on Kafka broker acknowledgement (producer configured with `acks=all` and
   `enable.idempotence=true`), the row transitions to `PUBLISHED` with `published_at` set. On
   failure, the row is either rescheduled (`NEW`, with backoff applied) or marked `FAILED` once
   retries are exhausted.

The claim lease (`outbox.publisher.claim-lease`, default `30s`) is kept comfortably above the
producer's `delivery.timeout.ms` (`20s`), so a legitimately in-flight send is never reclaimed and
re-published concurrently by another instance.

## Delivery Semantics

- Kafka delivery from the outbox publisher to `auth.events` is **at-least-once**: a broker
  acknowledgement race, a reclaimed-then-retried row, or a producer retry can all result in the
  same logical event being published (or observed) more than once.
- **Consumer idempotency is required** and is implemented today: `ledger-service` records every
  consumed `eventId` in a `processed_event` table via `INSERT ... ON CONFLICT DO NOTHING`; the first
  delivery projects the event, and any redelivery of the same `eventId` is detected and skipped.
  This gives effectively-once projection semantics on top of at-least-once delivery. See
  [Deduplication](#deduplication) below.

## Retry Policy

Defined in `OutboxBackoffPolicy`:

- Backoff schedule: `10s` after attempt 1, `30s` after attempt 2, `60s` after attempt 3, `300s`
  after attempt 4 and beyond.
- Max attempts: after the 5th failed publish attempt, the row is marked terminally `FAILED`.
- **Poison handling**: there is no separate dead-letter table for outbox/producer-side failures.
  A `FAILED` row simply stays in `outbox_event` with its `last_error` populated, and requires a
  manual operator decision to requeue (reset to `NEW`/`retry_count = 0`) or leave terminally
  failed for audit. See [Runbook links](#runbook-links).
- This is a different concept from the ledger-service *consumer-side* Kafka DLT topic
  (`auth.events.ledger.dlt`), which handles Kafka-consumption failures, not publish failures — see
  [`kafka-topics.md`](./kafka-topics.md).

## Deduplication

**Producer side:**

- `event_id` (UUID) uniquely identifies each event and is sent as a Kafka header on every record.
- The Kafka producer is configured with `enable.idempotence: true` and `acks: all`, preventing the
  producer itself from creating duplicate broker-side writes on retry.
- The unique DB index on `(aggregate_id, event_type, idempotency_key)` prevents a duplicate outbox
  row from ever being created for the same logical domain event in the first place.

**Consumer side:**

- `ledger-service` maintains a `processed_event` table keyed by `event_id`. Each inbound message is
  processed via `tryInsertProcessedEvent(...)`, an atomic `INSERT ... ON CONFLICT DO NOTHING`: the
  first delivery inserts the row and proceeds to project the event; any later redelivery of the
  same `event_id` fails the insert (no-op) and is skipped without reprocessing.
- Combined with manual offset commits (`enable-auto-commit: false`, `ack-mode: record`) and
  `isolation.level: read_committed` on the consumer, this gives effectively-once ledger
  projections despite at-least-once Kafka delivery.

## Failure Modes

| Scenario                                     | Behavior today                                                                                     |
|-----------------------------------------------|-----------------------------------------------------------------------------------------------------|
| **Broker down / unreachable**                | Publish attempts fail; `retry_count` increments and `next_attempt_at` is pushed out per the backoff schedule. Once the broker recovers, the next scheduler cycle picks the row up again. |
| **Partial publish** (send succeeds, ack lost, or instance crashes before marking `PUBLISHED`) | The claim lease (`claim_until`) bounds how long a row can sit `PUBLISHING` before being reclaimed to `NEW` and retried. Producer idempotence (`enable.idempotence: true`) means a retried send for the same logical record does not create a duplicate broker-side write; downstream consumer dedup (`processed_event`) is the final safety net against any residual duplicate delivery. |
| **Stuck / terminally failing rows**          | After 5 failed attempts a row is marked `FAILED` and stops being retried automatically. It remains visible in the table (not silently dropped) for operator triage via `last_error`, `retry_count`, and correlation id. |

## Operational Metrics

Exposed by `auth-service` via Micrometer/Prometheus (`/actuator/prometheus`):

- `auth_outbox_backlog` — gauge, live count of `NEW` + `PUBLISHING` rows (unsent/in-flight count).
  Note: `FAILED` rows are **not** counted here, so this metric alone does not surface terminal
  failures.
- `auth_outbox_publish_lag_seconds` — histogram, time from `created_at` to successful publish.
- `auth_outbox_publish_attempts_total{result="success"|"retry"|"failed"}` — counter, per-attempt
  outcome; `result="retry"` and `result="failed"` together give the retry/failure rate.
- Visualized in the `payment-platform-overview.json` Grafana dashboard
  (`infra/grafana/dashboards/`).

## Runbook Links

- [`outbox-backlog-recovery.md`](../flows/outbox-backlog-recovery.md) — detect/triage/retry/manual-replay
  procedure for a growing backlog or a terminally `FAILED` row, including the direct SQL query to
  find rows the `auth_outbox_backlog` gauge doesn't surface.
- [`outbox-backlog-recovery-flow.mmd`](../flows/outbox-backlog-recovery-flow.mmd) — decision-logic
  flowchart companion to the above.
- [`event-publishing-sequence.mmd`](../flows/event-publishing-sequence.mmd) — sequence diagram for
  the normal publish/retry/fail path.
- [`failure-scenarios.md`](../flows/failure-scenarios.md) — scenario 3 covers an outbox publish
  transient failure end-to-end.
- [ADR 0001: Use Kafka + Transactional Outbox](../decisions/0001-use-kafka-outbox.md)

## Roadmap / Production Considerations

The current implementation is deliberately scoped for an MVP demo. Recommended production
improvements are tracked in [`docs/roadmap.md`](../roadmap.md) (Production Considerations and Next
Steps sections), including:

- An admin endpoint or scheduled "cool-down retry" job to auto-requeue `FAILED` rows instead of a
  manual SQL update.
- A genuine dead-letter table/topic for producer-side failures that preserves full context
  (payload, complete error/attempt history) rather than overwriting `retry_count`/`status` in
  place.
- Authenticated/encrypted transport for Kafka (SASL/mTLS) and topic-level ACLs — see the Security
  section of [`kafka-topics.md`](./kafka-topics.md#securitygovernance) and
  [`docs/roadmap.md`](../roadmap.md#6-production-considerations).


