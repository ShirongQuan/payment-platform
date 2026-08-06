# System Overview

## Purpose

This project is an MVP payment-platform that demonstrates event-driven service boundaries and reliability patterns for
payment authorisation and ledger projection.

## Scope

In scope for the current implementation:

- account creation
- payment authorisation
- full capture
- event publishing through Kafka
- ledger ingestion for audit and reporting

Out of scope (or not yet implemented):

- reverse workflow implementation (endpoint currently placeholder)
- partial capture
- partial reverse
- refunds
- settlement
- reconciliation workflows

## High-Level Architecture

System context / high-level architecture diagram:

- [`system-overview.mmd`](system-overview.mmd)

Core platform components:

- API client
- Authorisation Service
- Fraud Service
- Auth DB
- Fraud DB
- Outbox table / publisher
- Kafka
- Ledger Service
- Ledger DB

Service/component interaction view:

- [`service-interaction.mmd`](service-interaction.mmd)

## Service Responsibilities

### Authorisation Service

Responsible for:

- validating requests
- managing authorisation state transitions
- updating account balances and reserved funds
- persisting authorisation events
- writing integration events to the outbox table

Owns:

- accounts
- authorisations
- authorisation_events
- outbox_event

### Fraud Service

Responsible for:

- evaluating payment risk and returning risk decisions

Owns:

- fraud service operational/risk data (`fraud_db`)

### Ledger Service

Responsible for:

- consuming authorisation-related Kafka events
- persisting raw event payloads
- projecting events into normalized ledger entries
- deduplicating processed events

Owns:

- ledger_event_log
- ledger_entries
- processed_events

The Ledger Service does not require in-order delivery for ingestion.
It persists each event independently as an immutable record and relies on event timestamps for historical
reconstruction.

## Runtime Interaction Model

The system uses two communication styles:

1. **Synchronous HTTP**
    - clients send authorise/capture requests to the Authorisation Service
    - Authorisation Service calls Fraud Service for risk evaluation where required

2. **Asynchronous messaging**
    - Authorisation Service persists business state + outbox event in one transaction
    - Outbox scheduler publishes pending events to Kafka
    - Ledger Service consumes and projects events into read models

## Data Ownership Boundaries

Each service owns its own database schema and tables.

- `auth-service` writes only `auth_db`
- `fraud-service` writes only `fraud_db`
- `ledger-service` writes only `ledger_db`
- services do not perform cross-database writes
- cross-service data propagation is event-driven through Kafka

## Main Workflows

The main business workflows are:

- authorise (implemented)
- capture (implemented)
- event publishing to ledger (implemented)
- reverse (planned / placeholder path)

For step-by-step interactions, see:

- [`../flows/authorise-sequence.mmd`](../flows/authorise-sequence.mmd)
- [`../flows/capture-sequence.mmd`](../flows/capture-sequence.mmd)
- [`../flows/reverse-sequence.mmd`](../flows/reverse-sequence.mmd)
- [`../flows/event-publishing.mmd`](../flows/event-publishing.mmd)
- [`../flows/event-consuming.mmd`](../flows/event-consuming.mmd)

## Reliability Patterns

### Idempotency

- Command idempotency keys protect retries on authorise/capture requests.
- Ledger consumer dedup uses `processed_events` keyed by event id.

### Transactional Outbox

- Auth service writes domain changes and outbox event in the same transaction.
- Scheduler/worker asynchronously publishes outbox rows to Kafka.

### Consumer Deduplication

- Ledger service records processed event ids before projection writes.
- Duplicate Kafka deliveries are safely ignored.

### Retry and Failure Isolation

- Outbox rows include retry state (`retry_count`, `next_attempt_at`, `last_error`) and failure terminal state.
- Kafka consumer errors are handled via retry/error handler and dead-letter topic strategy.

## Idempotency and Concurrency Summary

- Authorisation Service requires idempotency keys for command retries.
- Database uniqueness constraints guard duplicate processing under concurrent requests.
- Ledger Service deduplicates consumed events by `event_id` using `processed_events`.
- Transactional outbox pattern ensures reliable event publication from auth DB to Kafka.

## MVP Constraints

To keep implementation focused, the MVP intentionally uses:

- full capture only
- no partial capture/reverse
- simple authorisation status model
- single ledger projection service
- limited downstream consumers

These constraints keep the domain model understandable while still demonstrating realistic reliability and event-driven
patterns.

## Links To Deeper Docs

- Auth data model: [`../data/data-model.md`](../data/data-model.md)
- Flow diagrams index: [`../flows/README.md`](../flows/README.md)
- Authorization flow diagram: [`Payment-authorization-flow.mmd`](Payment-authorization-flow.mmd)
- Concurrency/idempotency sequence: [`concurrency-idempotency.mmd`](concurrency-idempotency.mmd)
- Simplified architecture view: [`simplified-high-level-architecture.mmd`](simplified-high-level-architecture.mmd)
