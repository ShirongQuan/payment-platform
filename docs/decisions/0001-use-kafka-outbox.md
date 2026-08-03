# ADR 0001: Use Kafka + Transactional Outbox

- Status: Accepted
- Date: 2026-07-31

## Context

The auth service must persist business state changes (authorisation, capture, reverse) and publish related domain events for downstream consumers (ledger). If event publishing is done directly from request code, failures can create a mismatch between database state and published events.

## Decision

Use Kafka for inter-service event delivery and the transactional outbox pattern in auth-service:
- write domain state + outbox row in the same database transaction
- publish outbox rows asynchronously to Kafka
- mark outbox rows as published only after broker acknowledgement

## Consequences

Positive:
- avoids dual-write inconsistency between DB and Kafka
- supports retries without losing business events
- decouples request latency from broker/network conditions

Trade-offs:
- adds outbox table, scheduler, and retry lifecycle complexity
- events are eventually consistent for downstream projections

## Related

- `docs/architecture/system-overview.md`
- `docs/flows/event-publishing.mmd`
- `docs/data/data-model.md`

