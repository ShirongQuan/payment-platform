# Kafka Topics

## Table of Contents

- [Topic Catalogue](#topic-catalogue)
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

## Topic Catalogue

| Topic                    | Producer                                                                   | Consumer(s)                                                      | Payload schema                                                                                                                                                                                                                                                     | Retention                                                                                                                                         |
|--------------------------|----------------------------------------------------------------------------|------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------|
| `auth.events`            | `auth-service` (`OutboxKafkaPublisher`)                                    | `ledger-service` (`LedgerKafkaConsumer`, group `ledger-service`) | JSON body per `event_type`: `AuthorisationAuthorisedPayload`, `AuthorisationCapturedPayload`, `AuthorisationReversedPayload`, plus headers (`eventId`, `aggregateType`, `aggregateId`, `eventType`, `occurredAt`, `correlationId`, `schemaVersion`, `traceparent`) | 6 partitions, replication factor 3 (`infra/kafka/create-topics.sh`); default broker retention (no topic-level `retention.ms` override configured) |
| `auth.events.ledger.dlt` | `ledger-service` (`DeadLetterPublishingRecoverer`, on consumer exhaustion) | none today (operator inspection only)                            | Original record key, value and relevant headers, with additional dead-letter diagnostic headers supplied by Spring Kafka after retry exhaustion                                                                                                                    | 6 partitions, replication factor 3 (`infra/kafka/create-topics.sh`); default broker retention                                                     |

Both topics are created by `infra/kafka/create-topics.sh` at environment bring-up.

## Message Keys

| Topic                    | Key                                                                                  | Why                                                                                                                                                                     |
|--------------------------|--------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `auth.events`            | `aggregateId` (the authorisation id, `UUID`)                                         | Routes events for the same authorisation to the same partition while the partition count and partitioning strategy remain unchanged, supporting per-aggregate ordering. |
| `auth.events.ledger.dlt` | Same key as the original record, republished to the same partition number on the DLT | Preserves the original partition number for inspection; DLT arrival order is not a substitute for the original processing order, especially when replaying records.     |

Producer key type is `UUID` (`UUIDSerializer`); consumer key type is correspondingly `UUID`
(`UUIDDeserializer`).

## Partitioning Strategy

- `auth.events` and `auth.events.ledger.dlt` are both provisioned with **6 partitions** and
  **replication factor 3** (`infra/kafka/create-topics.sh`), sized for the current MVP/demo
  throughput and the local 3-broker Kafka cluster.
- The partition count limits the number of active consumers in one consumer group that can process
  this topic in parallel. Events for a single authorisation are assigned to one partition and
  processed sequentially within that partition, assuming a stable partitioning strategy.
- Partition count is set at topic creation but can be increased later. Increasing it can change the
  partition chosen for **existing keys on subsequent sends**, while older records stay on their
  original partitions. This can break per-authorisation ordering, so changes require a migration
  plan rather than reactive scaling.

## Ordering Contracts

- **Ordered within a stable partitioning configuration**: events sharing the same key
  (`aggregateId`) are routed to the same partition while the partition count and key partitioner
  remain unchanged. Kafka preserves append order within that partition. The application relies on
  this for per-authorisation sequencing; partition-count or partitioner changes require a migration
  plan. This does not independently guarantee ordering across concurrent producer sends.
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
  [Outbox Pattern](./outbox-pattern.md#deduplication) for the full mechanism.
- **Offset commit behaviour**: `enable-auto-commit: false` with `ack-mode: record` — the consumer
  only advances its offset after a record has been fully handled (either successfully projected, or
  routed to the DLT after retry exhaustion), so a mid-processing crash results in redelivery rather
  than silent message loss.
- **Retry/DLT behaviour**: on a processing failure, `ledger-service`'s Kafka error handler retries
  the record **3 times after the initial delivery attempt, with a fixed 2-second backoff**
  (`ledger-service/src/main/java/org/example/ledger/KafkaConsumerConfig.java`). Non-retryable
  failures (e.g. malformed JSON, missing required headers) are **not retried** and go straight to
  the DLT. Once retries are exhausted, the record is republished to `auth.events.ledger.dlt` on the
  same partition number via `DeadLetterPublishingRecoverer`, and
  `ledger_kafka_dlt_published_total{topic,dltTopic,exceptionClass}` is incremented so the DLT rate
  is directly observable in Grafana.
- **Transactional isolation**: the consumer uses `isolation.level: read_committed`, which excludes
  aborted Kafka transactional records. This is independent of the producer's
  `enable.idempotence=true` setting; the current outbox publisher does not use Kafka transactions
  to achieve atomicity with PostgreSQL.

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

- **When allowed**: reprocessing an already committed event is idempotent while its
  `processed_event` marker is retained; the same `eventId` does not create another projection.
  Replaying a DLT record requires diagnosing and fixing the original failure first. Preserve its
  original event ID, key and relevant headers, and assess whether later events for the same
  authorisation have already been processed before republishing. Deduplication alone does not
  make an out-of-order replay safe.
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
  [Outbox Backlog Recovery](../flows/outbox-backlog-recovery.md) for the analogous
  producer-side (outbox) manual-replay procedure.

## Security/Governance

- **Current state**: the local/demo Kafka cluster (`infra/kafka`) runs without SASL authentication,
  without TLS encryption, and without topic-level ACLs — any client with network access to the
  brokers can produce/consume on either topic. This is an accepted, explicitly-documented scope cut
  for the current single-host `docker-compose` MVP topology, not an oversight — see
  [Payment Platform MVP Progress § Production Considerations](../roadmap.md#6-production-considerations).
- **PII**: `auth.events` payloads carry payment/authorisation domain fields (account id,
  authorisation id, amounts, currency, event reason) rather than end-customer PII (name, card
  number, address); no additional field-level encryption or masking is applied today beyond
  standard transport within the trusted docker-compose network.
- **Recommended production posture** (tracked in the roadmap as planned future work):
    - SASL/mTLS authentication and encrypted transport for all broker connections.
    - Topic-level Kafka ACLs granting `auth-service` write access to `auth.events`,
      `ledger-service` read access to `auth.events`, and the ledger DLT recoverer write access
      to `auth.events.ledger.dlt`; restrict DLT reads to authorised operators or replay tooling.
    - A field-level review before adding any customer-identifying data to event payloads, plus
      encryption-at-rest guidance if that ever becomes necessary.

## Roadmap / Production Considerations

See [Payment Platform MVP Progress § Production Considerations](../roadmap.md#6-production-considerations) for
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

- [Outbox Pattern](./outbox-pattern.md) — how events are reliably produced onto `auth.events`
  in the first place.
- [ADR 0004: Use Separate Main Event and DLT Topics](../decisions/0004-use-event-and-dlt-topics.md)
- <p>
    <a href="https://raw.githubusercontent.com/ShirongQuan/payment-platform/main/docs/flows/diagrams/event-consuming.svg">
  Event Consuming Diagram
    </a>
  </p>

- [API docs — Idempotency](../api/README.md#idempotency) — consumer-side dedup contract in the broader
  idempotency design.


