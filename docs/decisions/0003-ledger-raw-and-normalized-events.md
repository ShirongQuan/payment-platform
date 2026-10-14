# ADR 0003: Store Both Raw and Normalized Ledger Events

- Status: Accepted
- Date: 2026-07-31

## Context

`ledger-service` consumes payment events asynchronously from Kafka and maintains its own ledger data boundary. That boundary needs two complementary representations:

- raw event history preserving what the consumer received for replay and diagnosis
- normalized, query-friendly records for ledger API access patterns

Separating these representations lets ingestion history and the read model serve different purposes.

## Decision

In `ledger-service`, independently persist the consumed event into both:

- raw event records in `ledger_event_log`
- normalized projection rows in `ledger_entry`

Also store processed event ids in `processed_event` for consumer idempotency.

## Consequences

Positive:

- replay and diagnostic support from retained raw payload history
- efficient query/read endpoints using normalized rows
- clear separation between asynchronous event ingestion and the ledger read model
- ledger data remains owned and maintained by `ledger-service` rather than read from `auth-service`

Trade-offs:

- duplicated storage and write amplification
- extra mapping/projection logic to maintain

## Related

- [Data Model Overview](../data/data-model.md)
- [Ledger Service API](../api/ledger-api.md)
- <a href="https://raw.githubusercontent.com/ShirongQuan/payment-platform/main/docs/flows/diagrams/event-consuming.svg">Event Consuming Sequence</a> (sequence diagram)

