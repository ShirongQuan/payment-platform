# ADR 0003: Store Both Raw and Normalized Ledger Events

- Status: Accepted
- Date: 2026-07-31

## Context

Ledger consumers need two different capabilities:

- immutable audit/replay of exactly what was received from Kafka
- query-friendly records for API access patterns

A single table shape cannot optimize both use cases well.

## Decision

In ledger-service, store both:

- raw event records in `ledger_event_log`
- normalized projection rows in `ledger_entry`

Also store processed event ids in `processed_event` for consumer idempotency.

## Consequences

Positive:

- strong auditability and replay/debug support from raw payload history
- efficient query/read endpoints using normalized rows
- clearer separation between ingestion evidence and read model

Trade-offs:

- duplicated storage and write amplification
- extra mapping/projection logic to maintain

## Related

- [Data Model Overview](../data/data-model.md)
- [Ledger Service API](../api/ledger-api.md)
- <a href="https://raw.githubusercontent.com/ShirongQuan/payment-platform/main/docs/flows/diagrams/event-consuming.svg">Event Consuming Sequence</a> (sequence diagram)

