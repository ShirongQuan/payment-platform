# Flow Diagrams

This folder contains Mermaid sequence diagrams for the key runtime flows across `auth-service` and `ledger-service`.

## Which Diagram To Use

- [`authorise-sequence.mmd`](authorise-sequence.mmd)
  - Use when working on **initial authorisation** behavior.
  - Covers request handling, idempotency checks, account reserve logic, event persistence, and outbox enqueue.

- [`capture-sequence.mmd`](capture-sequence.mmd)
  - Use when working on **capture** behavior.
  - Covers legal-state validation, idempotent replay/conflict behavior, reserved balance capture, and capture outbox event creation.

- [`reverse-sequence.mmd`](reverse-sequence.mmd)
  - Use when discussing or implementing **reverse** behavior.
  - Shows the current placeholder state and the intended target transaction flow for reversal.

- Event publishing to Ledger:
  - [`event-publishing.mmd`](event-publishing.mmd)
    - Use for **auth-service outbox publishing** concerns (polling, claiming, retry/fail, Kafka publish).
  - [`event-consuming.mmd`](event-consuming.mmd)
    - Use for **ledger-service consume/projection** concerns (header parsing, routing, deduplication, and projection writes).

## Quick Guidance

- Start with `authorise-sequence.mmd` or `capture-sequence.mmd` for API/business logic changes.
- Use `event-publishing.mmd` for delivery reliability/debugging on producer side.
- Use `event-consuming.mmd` for consumer-side issues, duplicate handling, or ledger projection discrepancies.
- Use `reverse-sequence.mmd` as a design reference until reverse implementation is completed.

