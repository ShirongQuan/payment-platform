# Kafka Topics

## Table of Contents

- [Topic Catalog](#topic-catalog)
- [Message Keys](#message-keys)
- [Partitioning Strategy](#partitioning-strategy)
- [Ordering Contracts](#ordering-contracts)
- [Schema/Versioning](#schemaversioning)
- [Consumer Contract](#consumer-contract)
- [Delivery Semantics](#delivery-semantics)
- [Reprocessing/Replay Guidance](#reprocessingreplay-guidance)
- [Security/Governance](#securitygovernance)
- [Roadmap / Production Considerations](#roadmap--production-considerations)
- [Related](#related)

## Topic Catalog

| Topic                       | Producer       | Consumer(s)                     | Payload schema                                                                 | Retention                          |
|------------------------------|----------------|----------------------------------|----------------------------------------------------------------------------------|--------------------------------------|
| `auth.events`                | `auth-service` (`OutboxKafkaPublisher`) | `ledger-service` (`LedgerKafkaConsumer`, group `ledger-service`) | JSON body per `event_type`: `AuthorisationAuthorisedPayload`, `AuthorisationCapturedPayload`, `AuthorisationReversedPayload`, plus headers (`eventId`, `aggregateType`, `aggregateId`, `eventType`, `occurredAt`, `correlationId`, `schemaVersion`, `traceparent`) | 6 partitions, replication factor 3 (`infra/kafka/create-topics.sh`); default broker retention (no topic-level `retention.ms` override configured) |
| `auth.events.ledger.dlt`     | `ledger-service` (`DeadLetterPublishingRecoverer`, on consumer exhaustion) | none today (operator inspection only) | Same JSON payload/headers as the originating `auth.events` record, republished as-is after retry exhaustion | 6 partitions, replication factor 3 (`infra/kafka/create-topics.sh`); default broker retention |

Both topics are created by `infra/kafka/create-topics.sh` at environment bring-up.

## Message Keys

| Topic                   | Key                          | Why                                                                                                  |
|--------------------------|-------------------------------|--------------------------------------------------------------------------------------------------------|
| `auth.events`             | `aggregateId` (the authorisation id, `UUID`) | Guarantees all events for the same authorisation (e.g. `AUTHORISED` → `CAPTURED`/`REVERSED`) land on the same partition, preserving per-aggregate ordering for the ledger consumer's projection logic. |
| `auth.events.ledger.dlt`  | Same key as the original record, republished to the same partition number on the DLT | Preserves the same per-aggregate ordering property on the DLT so replay/inspection tooling can reason about ordering the same way as the main topic. |

Producer key type is `UUID` (`UUIDSerializer`); consumer key type is correspondingly `UUID`
(`UUIDDeserializer`).

## Partitioning Strategy

- `auth.events` and `auth.events.ledger.dlt` are both provisioned with **6 partitions** and
  **replication factor 3** (`infra/kafka/create-topics.sh`), sized for the current MVP/demo
  throughput and the local 3-broker Kafka cluster.
- Because the key is `aggregateId`, partition count is the practical upper bound on **consumer
  parallelism per aggregate stream**: increasing partitions lets more `ledger-service` consumer
  instances (within the same consumer group) process different aggregates concurrently, while
  still guaranteeing all events for any single authorisation stay in order on one partition.
- Partition count is set at topic-creation time; changing it later only affects the partitioning of
  new keys going forward (existing keys are not automatically rebalanced across the new partition
  count), so it is chosen up front based on expected consumer group size rather than adjusted
  reactively.

## Ordering Contracts

- **Ordered**: events sharing the same key (`aggregateId`) are ordered relative to each other,
  because they are always routed to the same partition and a partition preserves write order.
  Consumers can therefore rely on an authorisation's own event sequence (e.g. `AUTHORISED` observed
  before a later `CAPTURED`/`REVERSED` for that same authorisation) arriving in order.
- **Not guaranteed**: there is no ordering guarantee **across different aggregates** (different
  authorisation ids), since they may land on different partitions and be consumed independently. No
  global, cross-topic, or cross-partition ordering is provided or relied upon.

## Schema/Versioning

- Payloads are plain JSON (Spring Kafka `JsonSerializer` producer-side,
  `StringDeserializer` + manual JSON handling consumer-side) — there is currently **no schema
  registry and no Avro/Protobuf schema enforcement**.
- Every record carries a `schemaVersion` header (currently `"1"`), giving consumers an explicit,
  cheap signal to branch on if the payload shape changes in the future, without requiring a schema
  registry round-trip.
- Compatibility today is maintained by convention: existing fields in `AuthorisationAuthorisedPayload`,
  `AuthorisationCapturedPayload`, and `AuthorisationReversedPayload` are additive-only in practice —
  new optional fields can be added without breaking `ledger-service`'s deserialization, since it
  reads named fields rather than relying on positional/strict schema matching.
- A formal backward/forward-compatibility policy (e.g. schema registry with compatibility mode
  enforcement) is recommended before onboarding additional consumers or evolving the payload shape
  further — see [Roadmap](#roadmap--production-considerations).

## Consumer Contract

- **Idempotency expectation**: consumers of `auth.events` must tolerate at-least-once delivery.
  `ledger-service` satisfies this today via a `processed_event` table keyed on `event_id`
  (`INSERT ... ON CONFLICT DO NOTHING`), giving effectively-once projection semantics — see
  [`outbox-pattern.md`](./outbox-pattern.md#deduplication) for the full mechanism.
- **Offset commit behavior**: `enable-auto-commit: false` with `ack-mode: record` — the consumer
  only advances its offset after a record has been fully handled (either successfully projected, or
  routed to the DLT after retry exhaustion), so a mid-processing crash results in redelivery rather
  than silent message loss.
- **Retry/DLT behavior**: on a processing failure, `ledger-service`'s Kafka error handler retries
  the record **3 times with a fixed 2-second backoff**
  (`ledger-service/src/main/java/org/example/ledger/KafkaConsumerConfig.java`). Non-retryable
  failures (e.g. malformed JSON, missing required headers) are **not retried** and go straight to
  the DLT. Once retries are exhausted, the record is republished to `auth.events.ledger.dlt` on the
  same partition number via `DeadLetterPublishingRecoverer`, and
  `ledger_kafka_dlt_published_total{topic,dltTopic,exceptionClass}` is incremented so the DLT rate
  is directly observable in Grafana.
- **Transactional isolation**: the consumer runs with `isolation.level: read_committed`, so it only
  ever observes committed producer records — consistent with the producer's `enable.idempotence:
  true` configuration on the `auth-service` side.

## Delivery Semantics

- `auth.events` delivery is **at-least-once** end to end: the outbox publisher can redeliver on a
  reclaimed claim or producer retry, and the consumer can redeliver on a rebalance or restart before
  an offset commit.
- The practical implication is that **every consumer of `auth.events` must implement its own
  deduplication** (by `event_id`) rather than assuming exactly-once delivery; `ledger-service`'s
  `processed_event` table is the reference implementation of this contract today.
- `auth.events.ledger.dlt` inherits the same at-least-once characteristics as the topic it
  originates from; a record can, in principle, be republished to the DLT more than once if a
  producer-side retry occurs during the DLT publish itself.

## Reprocessing/Replay Guidance

- **When allowed**: replaying a record from `auth.events.ledger.dlt` (or reprocessing an
  already-delivered `auth.events` record) is safe by design, because `ledger-service`'s
  `processed_event` dedup guard makes redelivery of an already-projected `event_id` a no-op.
  Replay is therefore appropriate once the root cause of a DLT-routed failure (e.g. a bug that
  rejected a previously-valid payload) has been fixed.
- **Safeguards already in place**:
    - The `processed_event` unique constraint on `event_id` prevents a replay from double-applying
      a ledger projection.
    - DLT records retain the original key (`aggregateId`) and headers (`eventId`, `correlationId`,
      etc.), so a replayed/reprocessed record can still be correlated back to its originating
      authorisation and request trace.
- **Not yet automated**: there is no built-in "replay from DLT" tool/endpoint today — reprocessing a
  DLT record is a manual operational action (e.g. re-publishing the DLT record's payload back onto
  `auth.events`, or a targeted consumer re-run). See [Roadmap](#roadmap--production-considerations)
  for planned improvements, and
  [`outbox-backlog-recovery.md`](../flows/outbox-backlog-recovery.md) for the analogous
  producer-side (outbox) manual-replay procedure.

## Security/Governance

- **Current state**: the local/demo Kafka cluster (`infra/kafka`) runs without SASL authentication,
  without TLS encryption, and without topic-level ACLs — any client with network access to the
  brokers can produce/consume on either topic. This is an accepted, explicitly-documented scope cut
  for the current single-host `docker-compose` MVP topology, not an oversight — see
  [`docs/roadmap.md` § Production Considerations](../roadmap.md#6-production-considerations).
- **PII**: `auth.events` payloads carry payment/authorisation domain fields (account id,
  authorisation id, amounts, currency, event reason) rather than end-customer PII (name, card
  number, address); no additional field-level encryption or masking is applied today beyond
  standard transport within the trusted docker-compose network.
- **Recommended production posture** (tracked in the roadmap, not yet implemented):
    - SASL/mTLS authentication and encrypted transport for all broker connections.
    - Topic-level Kafka ACLs so only `auth-service` can produce to `auth.events` and only
      `ledger-service` (and its DLT recoverer) can produce/consume the DLT topic.
    - A field-level review before adding any customer-identifying data to event payloads, plus
      encryption-at-rest guidance if that ever becomes necessary.

## Roadmap / Production Considerations

See [`docs/roadmap.md` § Production Considerations](../roadmap.md#6-production-considerations) for
the full list of deliberate MVP scope cuts. Kafka-specific items tracked there and recommended
before a production deployment:

- Authenticated/encrypted transport for Kafka (SASL/mTLS) and topic-level ACLs.
- A schema registry (or equivalent Avro/Protobuf enforcement) with an explicit
  backward/forward-compatibility policy, superseding today's convention-based JSON compatibility
  and `schemaVersion` header.
- Tooling/automation for DLT replay instead of the current manual reprocessing step.
- SLOs/alerting on DLT publish rate and consumer lag (metrics already exist —
  `ledger_kafka_dlt_published_total` — but no alerting rules are defined yet).

## Related

- [`outbox-pattern.md`](./outbox-pattern.md) — how events are reliably produced onto `auth.events`
  in the first place.
- [ADR 0004: Use Separate Main Event and DLT Topics](../decisions/0004-use-event-and-dlt-topics.md)
- [`docs/flows/event-consuming.mmd`](../flows/event-consuming.mmd)
- [`docs/api/idempotency.md`](../api/idempotency.md) — consumer-side dedup contract in the broader
  idempotency design.


