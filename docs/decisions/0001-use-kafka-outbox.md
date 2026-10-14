# ADR 0001: Use Kafka + Transactional Outbox

- Status: Accepted
- Date: 2026-07-31

## Context

`auth-service` owns the payment lifecycle state for authorisation, capture, and reverse, while `ledger-service` maintains an independent ledger projection from asynchronously delivered events. Coordinating the local database update with Kafka publication therefore crosses a distributed-system boundary: the database and broker cannot participate in one local transaction.

## Decision

Use Kafka for asynchronous inter-service event delivery and the transactional outbox pattern in `auth-service`:
- commit payment state and the corresponding outbox row atomically in the same local Postgres transaction
- publish committed outbox rows to Kafka asynchronously, outside the originating request transaction
- mark an outbox row as published only after broker acknowledgement

This keeps database consistency local to `auth-service` while providing a reliable bridge to asynchronous downstream processing without requiring a distributed transaction across Postgres and Kafka.

## Consequences

Positive:
- payment state and the intent to publish its event commit atomically inside the `auth-service` data boundary
- publication can be retried independently after the local transaction commits
- request-time database consistency is separated from asynchronous Kafka publication
- `ledger-service` can process payment events independently of the originating HTTP request

Trade-offs:
- adds outbox table, scheduler, and retry lifecycle complexity
- downstream projections are eventually consistent with committed payment state

## Related

- [System Context](../architecture/system-context.md)
- [Data Model Overview](../data/data-model.md)
- <a href="https://raw.githubusercontent.com/ShirongQuan/payment-platform/main/docs/flows/diagrams/event-publishing-sequence.svg">Event Publishing Sequence</a> (sequence diagram)

