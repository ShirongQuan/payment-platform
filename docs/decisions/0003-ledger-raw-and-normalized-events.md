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
- normalized projection rows in `ledger_entries`

Also store processed event ids in `processed_events` for consumer idempotency.

## Consequences

Positive:
- strong auditability and replay/debug support from raw payload history
- efficient query/read endpoints using normalized rows
- clearer separation between ingestion evidence and read model

Trade-offs:
- duplicated storage and write amplification
- extra mapping/projection logic to maintain

## Related

- `docs/data/data-model.md`
- `docs/flows/event-consuming.mmd`
- `docs/api/ledger-api.md`

